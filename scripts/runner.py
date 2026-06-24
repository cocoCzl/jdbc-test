#!/usr/bin/env python3
"""
JDBC Test Runner
用法: python scripts/runner.py [config.yaml]

功能:
  1. 读取配置文件
  2. 生成 junit-platform.properties
  3. 执行 mvn test
  4. 解析 Surefire XML 报告
  5. 生成 JSON 报告
  6. 归档到 report/{db_type}_{timestamp}/
"""

import yaml
import subprocess
import os
import sys
import json
import re
import platform
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

from compatibility_v1 import build_v1_report, generate_matrix, vendor_extension_report

PROJECT_ROOT = Path(__file__).resolve().parent.parent


def resolve_config_path(config_path=None):
    if config_path is None:
        config_path = os.environ.get("CONFIG_PATH")
        if config_path is None:
            local_config = PROJECT_ROOT / "configs" / "config.yaml"
            config_path = str(local_config if local_config.exists() else PROJECT_ROOT / "config.yaml")

    path = Path(config_path)
    if not path.is_absolute():
        path = PROJECT_ROOT / path

    if not path.exists():
        print(f"[错误] 配置文件不存在: {path}")
        sys.exit(1)

    return path


def load_config(config_path=None):
    path = resolve_config_path(config_path)

    with open(path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f)

    # 环境变量替换 ${VAR}
    def resolve_env(obj):
        if isinstance(obj, str):
            return re.sub(r'\$\{([^}]+)}', lambda m: os.environ.get(m.group(1), ""), obj)
        if isinstance(obj, dict):
            return {k: resolve_env(v) for k, v in obj.items()}
        if isinstance(obj, list):
            return [resolve_env(v) for v in obj]
        return obj

    config = resolve_env(config)
    config["_config_path"] = str(path)
    return config


def generate_junit_properties(config):
    concurrency = config.get("concurrency", {})
    parallel = concurrency.get("enabled", False)
    threads = concurrency.get("threads", 1)

    output_dir = PROJECT_ROOT / "target" / "test-classes"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_file = output_dir / "junit-platform.properties"

    lines = [
        f"junit.jupiter.execution.parallel.enabled = {'true' if parallel else 'false'}",
        "junit.jupiter.execution.parallel.mode.default = same_thread",
        f"junit.jupiter.execution.parallel.mode.classes.default = {'concurrent' if parallel else 'same_thread'}",
    ]
    if parallel and threads > 1:
        lines.append("junit.jupiter.execution.parallel.config.strategy = fixed")
        lines.append(f"junit.jupiter.execution.parallel.config.fixed.parallelism = {threads}")

    test_timeout = config.get("test_filter", {}).get("timeout")
    if test_timeout:
        lines.append(f"junit.jupiter.execution.timeout.test.method.default = {int(test_timeout)} ms")

    with open(output_file, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"[生成] {output_file} (并行: {parallel}, 线程: {threads})")
    return output_file


def run_maven_test(config):
    print("[执行] mvn test ...")
    env = os.environ.copy()
    config_path = config.get("_config_path", str(PROJECT_ROOT / "config.yaml"))
    env["CONFIG_PATH"] = config_path

    _install_custom_driver(config, env)

    cmd = ["mvn", "test", "-q", f"-Dconfig.yaml={config_path}"]
    include_tests = config.get("test_filter", {}).get("include_tests") or []
    exclude_tests = config.get("test_filter", {}).get("exclude_tests") or []
    test_patterns = list(include_tests) if include_tests else []
    if exclude_tests:
        if not test_patterns:
            test_patterns.append("*Test")
        test_patterns.extend(f"!{pattern}" for pattern in exclude_tests)
    if test_patterns:
        cmd.append(f"-Dtest={','.join(test_patterns)}")

    start_time = datetime.now()
    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        env=env,
        capture_output=True,
        text=True,
    )
    end_time = datetime.now()

    elapsed = (end_time - start_time).total_seconds()
    print(f"[完成] 耗时 {elapsed:.1f}s, 退出码 {result.returncode}")

    return {
        "start_time": start_time.isoformat(),
        "end_time": end_time.isoformat(),
        "elapsed_seconds": round(elapsed, 3),
        "exit_code": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
    }


def _install_custom_driver(config, env):
    """安装自定义 JDBC 驱动到本地 Maven 仓库"""
    custom = config.get("db", {}).get("custom_driver")
    if not custom:
        return

    load_method = custom.get("load_method", "")
    jar_path = custom.get("jar_path", "")

    if not jar_path:
        print("[警告] custom_driver.jar_path 未配置")
        return

    jar_file = Path(jar_path)
    if not jar_file.is_absolute():
        jar_file = PROJECT_ROOT / jar_path

    if not jar_file.exists():
        print(f"[错误] 自定义驱动 JAR 不存在: {jar_file}")
        sys.exit(1)

    if load_method == "local_repo":
        gid = custom.get("group_id", "com.custom")
        aid = custom.get("artifact_id", "custom-driver")
        ver = custom.get("version", "1.0")
        print(f"[安装] {jar_file.name} -> 本地仓库 ({gid}:{aid}:{ver})")
        result = subprocess.run(
            ["mvn", "install:install-file", "-q",
             f"-Dfile={jar_file}",
             f"-DgroupId={gid}",
             f"-DartifactId={aid}",
             f"-Dversion={ver}",
             "-Dpackaging=jar"],
            cwd=PROJECT_ROOT,
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            print(f"[错误] install:install-file 失败:\n{result.stderr}")
            sys.exit(1)
    elif load_method == "classpath":
        cp = env.get("SUREFIRE_ADDITIONAL_CLASSPATH", "")
        sep = ";" if platform.system() == "Windows" else ":"
        env["SUREFIRE_ADDITIONAL_CLASSPATH"] = f"{cp}{sep}{jar_file}" if cp else str(jar_file)


def run_docker_test(config):
    """在 Docker 容器中执行测试"""
    db = config.get("db", {})
    image_name = "jdbc-test-runner"
    container_name = f"jdbc-test-{datetime.now().strftime('%Y%m%d%H%M%S')}"
    m2_volume = "m2_repo"

    # 构建镜像
    print(f"[Docker] 构建镜像 {image_name}...")
    build_result = subprocess.run(
        ["docker", "build", "-t", image_name, "-q", "."],
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
    )
    if build_result.returncode != 0:
        print(f"[错误] Docker 镜像构建失败:\n{build_result.stderr}")
        sys.exit(1)
    print(f"[Docker] 镜像构建成功")

    # 准备环境变量
    docker_env = []
    if db.get("password"):
        docker_env.extend(["-e", f"DB_PASSWORD={db['password']}"])

    # 确保 m2 volume 存在
    subprocess.run(["docker", "volume", "create", m2_volume], capture_output=True)

    # 运行容器
    print(f"[Docker] 启动容器 {container_name}...")
    start_time = datetime.now()

    run_result = subprocess.run([
        "docker", "run", "--rm",
        "--name", container_name,
        "-v", f"{PROJECT_ROOT}:/project",
        "-v", f"{m2_volume}:/root/.m2",
        "--network", "host",
        *docker_env,
        image_name,
        "test", "-q",
    ], capture_output=True, text=True, timeout=config.get("concurrency", {}).get("timeout", 300000) // 1000)

    end_time = datetime.now()
    elapsed = (end_time - start_time).total_seconds()
    print(f"[Docker] 完成 耗时 {elapsed:.1f}s, 退出码 {run_result.returncode}")

    return {
        "start_time": start_time.isoformat(),
        "end_time": end_time.isoformat(),
        "elapsed_seconds": round(elapsed, 3),
        "exit_code": run_result.returncode,
        "stdout": run_result.stdout,
        "stderr": run_result.stderr,
    }


def parse_surefire_reports():
    surefire_dir = PROJECT_ROOT / "target" / "surefire-reports"
    if not surefire_dir.exists():
        print("[警告] surefire-reports 目录不存在")
        return [], {}

    env_info = {}
    test_suites = []

    for xml_file in sorted(surefire_dir.glob("TEST-*.xml")):
        try:
            tree = ET.parse(xml_file)
            root = tree.getroot()

            suite = {
                "name": root.get("name", ""),
                "time_seconds": float(root.get("time", 0)),
                "total": int(root.get("tests", 0)),
                "failures": int(root.get("failures", 0)),
                "errors": int(root.get("errors", 0)),
                "skipped": int(root.get("skipped", 0)),
                "system_err": root.findtext("system-err", default=""),
                "system_out": root.findtext("system-out", default=""),
                "testcases": [],
            }

            # 收集系统属性（只从第一个文件收集）
            if not env_info:
                props_elem = root.find("properties")
                if props_elem is not None:
                    for prop in props_elem.findall("property"):
                        env_info[prop.get("name", "")] = prop.get("value", "")

            # 解析测试用例
            for tc in root.findall("testcase"):
                case = {
                    "name": tc.get("name", ""),
                    "classname": tc.get("classname", ""),
                    "time_seconds": float(tc.get("time", 0)),
                }

                error_elem = tc.find("error")
                if error_elem is not None:
                    case["status"] = "error"
                    case["error_message"] = error_elem.get("message", "")
                    case["error_type"] = error_elem.get("type", "")
                    case["error_text"] = error_elem.text or ""
                elif tc.find("failure") is not None:
                    case["status"] = "failure"
                    f_elem = tc.find("failure")
                    case["error_message"] = f_elem.get("message", "")
                    case["error_text"] = f_elem.text or ""
                elif tc.find("skipped") is not None:
                    case["status"] = "skipped"
                    s_elem = tc.find("skipped")
                    case["skip_reason"] = s_elem.get("message", "") or s_elem.text or ""
                else:
                    case["status"] = "passed"

                suite["testcases"].append(case)

            test_suites.append(suite)

        except Exception as e:
            print(f"[警告] 解析 {xml_file.name} 失败: {e}")

    return test_suites, env_info


def extract_driver_info(env_info):
    """从 classpath 中提取驱动版本信息"""
    classpath = env_info.get("surefire.test.class.path", "")
    drivers = {}

    patterns = [
        (r"postgresql-([\d.]+)\.jar", "postgresql"),
        (r"mysql-connector-j-([\d.]+)\.jar", "mysql"),
        (r"ojdbc11-([\d.]+)\.jar", "oracle"),
        (r"mssql-jdbc-([\d.]+)\.jre\d+\.jar", "sqlserver"),
    ]

    for pattern, name in patterns:
        m = re.search(pattern, classpath)
        if m:
            drivers[name] = {
                "jar": f"{name}-{m.group(1)}",
                "version": m.group(1),
            }

    return drivers


def collect_environment_info(config, env_info):
    """收集环境信息"""
    db_config = config.get("db", {})
    drivers = extract_driver_info(env_info)

    current_driver = drivers.get(db_config.get("type", ""), {})
    runtime_driver_name = env_info.get("jdbc.test.driverName", "")
    runtime_driver_version = env_info.get("jdbc.test.driverVersion", "")

    return {
        "数据库类型": db_config.get("type", ""),
        "数据库产品名": env_info.get("jdbc.test.databaseProductName", ""),
        "数据库产品版本": env_info.get("jdbc.test.databaseProductVersion", ""),
        "配置JDBC URL": db_config.get("url", ""),
        "数据库用户名": db_config.get("username", ""),
        "JDBC连接URL": env_info.get("jdbc.test.jdbcUrl", ""),
        "JDBC驱动": runtime_driver_name or current_driver.get("jar", "未知"),
        "JDBC驱动版本": runtime_driver_version or current_driver.get("version", "未知"),
        "JDBC驱动类名": _get_driver_class(db_config.get("type", "")),
        "DDL脚本路径": config.get("ddl", {}).get("base_path", ""),
        "DML脚本路径": config.get("dml", {}).get("base_path", ""),
        "Java版本": env_info.get("java.version", ""),
        "Java规范版本": env_info.get("java.specification.version", ""),
        "Java供应商": env_info.get("java.vm.vendor", ""),
        "JVM名称": env_info.get("java.vm.name", ""),
        "操作系统": env_info.get("os.name", ""),
        "操作系统架构": env_info.get("os.arch", ""),
        "操作系统版本": env_info.get("os.version", ""),
        "用户名": env_info.get("user.name", ""),
        "用户语言": env_info.get("user.language", ""),
        "用户国家": env_info.get("user.country", ""),
        "文件编码": env_info.get("file.encoding", ""),
        "本地时区": env_info.get("user.timezone", ""),
        "主机名": platform.node(),
        "Python版本": sys.version,
        "执行时间": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }


def _get_driver_class(db_type):
    mapping = {
        "postgresql": "org.postgresql.Driver",
        "mysql": "com.mysql.cj.jdbc.Driver",
        "oracle": "oracle.jdbc.OracleDriver",
        "sqlserver": "com.microsoft.sqlserver.jdbc.SQLServerDriver",
    }
    return mapping.get(db_type, "")


def generate_json_report(config, execution, test_suites, env_info):
    """生成 JSON 报告"""
    total_tests = sum(s["total"] for s in test_suites)
    total_failures = sum(s["failures"] for s in test_suites)
    total_errors = sum(s["errors"] for s in test_suites)
    total_skipped = sum(s["skipped"] for s in test_suites)
    total_passed = total_tests - total_failures - total_errors - total_skipped

    environment = collect_environment_info(config, env_info)

    report = {
        "报告标题": "JDBC 接口测试报告",
        "生成时间": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "执行概要": {
            "开始时间": execution["start_time"],
            "结束时间": execution["end_time"],
            "总耗时秒": execution["elapsed_seconds"],
            "退出码": execution["exit_code"],
        },
        "测试汇总": {
            "测试类数量": len(test_suites),
            "测试方法总数": total_tests,
            "通过": total_passed,
            "失败": total_failures,
            "错误": total_errors,
            "跳过": total_skipped,
            "通过率": f"{(total_passed / total_tests * 100):.1f}%" if total_tests > 0 else "N/A",
        },
        "环境信息": environment,
        "测试类明细": [],
    }

    for suite in test_suites:
        suite_passed = suite["total"] - suite["failures"] - suite["errors"] - suite["skipped"]
        detail = {
            "类名": suite["name"],
            "耗时秒": suite["time_seconds"],
            "用例统计": {
                "总数": suite["total"],
                "通过": suite_passed,
                "失败": suite["failures"],
                "错误": suite["errors"],
                "跳过": suite["skipped"],
            },
            "用例明细": [],
        }

        for case in suite["testcases"]:
            case_detail = {
                "方法名": case["name"],
                "耗时秒": case["time_seconds"],
                "状态": case["status"],
            }
            if case["status"] == "error" or case["status"] == "failure":
                case_detail["错误信息"] = case.get("error_message", "")
                case_detail["错误类型"] = case.get("error_type", "")
                case_detail["堆栈"] = case.get("error_text", "")
            if case["status"] == "skipped":
                case_detail["跳过原因"] = case.get("skip_reason", "")
            detail["用例明细"].append(case_detail)

        report["测试类明细"].append(detail)

    return report


def generate_html_report(report):
    """生成 HTML 报告"""
    summary = report["测试汇总"]
    env = report["环境信息"]
    exec_info = report["执行概要"]
    v1 = report.get("v1_compatibility_report", {})

    status_style = {
        "passed": "color:#2e7d32;font-weight:bold",
        "failure": "color:#c62828;font-weight:bold",
        "error": "color:#e65100;font-weight:bold",
        "skipped": "color:#6a1b9a;font-weight:bold",
    }

    suite_rows = []
    for s in report["测试类明细"]:
        passed = s["用例统计"]["通过"]
        failed = s["用例统计"]["失败"]
        errors = s["用例统计"]["错误"]
        if passed == s["用例统计"]["总数"]:
            badge = '<span style="color:#2e7d32">&#10003;</span>'
        elif failed > 0 or errors > 0:
            badge = '<span style="color:#c62828">&#10007;</span>'
        else:
            badge = '<span style="color:#6a1b9a">&#9888;</span>'

        # 用例明细行
        case_rows = []
        for c in s["用例明细"]:
            st = c["状态"]
            emoji = {"passed": "&#10003;", "failure": "&#10007;", "error": "&#9888;", "skipped": "&#10140;"}.get(st, "")
            detail = ""
            if st in ("error", "failure"):
                detail = f'<br><small style="color:#c62828">{_html_escape(c.get("错误信息", ""))}</small>'
            elif st == "skipped":
                detail = f'<br><small style="color:#6a1b9a">原因: {_html_escape(c.get("跳过原因", ""))}</small>'
            case_rows.append(
                f'<tr><td style="padding-left:32px">{emoji} {_html_escape(c["方法名"])}</td>'
                f'<td style="{status_style.get(st, "")}">{st}</td>'
                f'<td>{c["耗时秒"]:.3f}s</td></tr>'
                + (f'<tr><td colspan="3" style="padding-left:48px;font-family:monospace;font-size:12px;white-space:pre-wrap;color:#c62828">{detail}</td></tr>' if detail else "")
            )

        suite_rows.append(
            f'<tr style="background:#f5f5f5"><td><b>{badge} {_html_escape(s["类名"])}</b></td>'
            f'<td>{s["用例统计"]["总数"]}</td>'
            f'<td style="color:#2e7d32">{passed}</td>'
            f'<td style="color:#c62828">{failed + errors}</td>'
            f'<td style="color:#6a1b9a">{s["用例统计"]["跳过"]}</td>'
            f'<td>{s["耗时秒"]:.3f}s</td></tr>'
            + "".join(case_rows)
        )

    env_rows = "".join(f"<tr><td><b>{k}</b></td><td>{_html_escape(str(v))}</td></tr>" for k, v in env.items())

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>JDBC 测试报告 - {env.get('数据库类型', '')}</title>
<style>
body {{ font-family: -apple-system, 'Microsoft YaHei', sans-serif; margin: 20px; background: #fafafa; color: #333; }}
.container {{ max-width: 1200px; margin: 0 auto; background: #fff; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); padding: 24px; }}
h1 {{ color: #1a237e; border-bottom: 3px solid #3f51b5; padding-bottom: 8px; }}
h2 {{ color: #283593; margin-top: 32px; }}
table {{ width: 100%; border-collapse: collapse; margin: 12px 0; }}
th, td {{ padding: 8px 12px; text-align: left; border-bottom: 1px solid #e0e0e0; }}
th {{ background: #3f51b5; color: #fff; font-weight: 600; }}
tr:hover {{ background: #f5f5f5; }}
.summary {{ display: flex; gap: 16px; margin: 16px 0; flex-wrap: wrap; }}
.card {{ border: 2px solid #e0e0e0; border-radius: 8px; padding: 16px; min-width: 140px; text-align: center; }}
.card .num {{ font-size: 36px; font-weight: bold; }}
.card.green {{ border-color: #4caf50; }} .card.green .num {{ color: #2e7d32; }}
.card.red {{ border-color: #f44336; }} .card.red .num {{ color: #c62828; }}
.card.orange {{ border-color: #ff9800; }} .card.orange .num {{ color: #e65100; }}
.card.purple {{ border-color: #9c27b0; }} .card.purple .num {{ color: #6a1b9a; }}
</style>
</head>
<body>
<div class="container">
<h1>JDBC 接口测试报告</h1>
<p>生成时间: {report['生成时间']} | 执行耗时: {exec_info['总耗时秒']}s</p>
{_v1_html_summary(v1)}

<div class="summary">
<div class="card green"><div class="num">{summary['通过']}</div><div>通过</div></div>
<div class="card red"><div class="num">{summary['失败'] + summary['错误']}</div><div>失败/错误</div></div>
<div class="card purple"><div class="num">{summary['跳过']}</div><div>跳过</div></div>
<div class="card"><div class="num">{summary['通过率']}</div><div>通过率</div></div>
</div>

<h2>测试明细</h2>
<table>
<tr><th>测试类</th><th>总数</th><th>通过</th><th>失败</th><th>跳过</th><th>耗时</th></tr>
{"".join(suite_rows)}
</table>

<h2>环境信息</h2>
<table>
<tr><th style="width:200px">项目</th><th>值</th></tr>
{env_rows}
</table>
</div>
</body>
</html>"""
    return html


def generate_markdown_report(report):
    """生成 Markdown 报告"""
    summary = report["测试汇总"]
    env = report["环境信息"]
    exec_info = report["执行概要"]
    v1 = report.get("v1_compatibility_report", {})

    lines = [
        f"# JDBC 接口测试报告",
        "",
        f"**数据库类型**: {env.get('数据库类型', '')} | **生成时间**: {report['生成时间']} | **总耗时**: {exec_info['总耗时秒']}s",
        "",
    ]
    if v1:
        lines += [
            "## v1 兼容性评估",
            "",
            "| 项目 | 值 |",
            "|------|------|",
            f"| Target Outcome | {v1.get('target_outcome', '')} |",
            f"| Run Kind | {v1.get('run_kind', '')} |",
            f"| Compatibility Baseline Version | {v1.get('compatibility_baseline_version', '')} |",
            f"| Report Schema Version | {v1.get('report_schema_version', '')} |",
            f"| Capability Profile | {(v1.get('capability_profile') or {}).get('completeness', 'N/A')} |",
            "",
            "### v1 场景结果",
            "",
            "| Scenario ID | Category | Compatibility Status | Source |",
            "|-------------|----------|----------------------|--------|",
        ]
        for scenario_id, result in v1.get("scenario_results", {}).items():
            source = result.get("source_class", "").split(".")[-1] + "." + result.get("source_method", "")
            lines.append(
                f"| {_md_escape(scenario_id)} | {result.get('category', '')} | "
                f"{result.get('compatibility_status', '')} | {_md_escape(source)} |"
            )
        lines.append("")

    lines += [
        "## 测试汇总",
        "",
        "| 指标 | 数值 |",
        "|------|------|",
        f"| 测试类 | {summary['测试类数量']} |",
        f"| 测试用例 | {summary['测试方法总数']} |",
        f"| 通过 | {summary['通过']} |",
        f"| 失败 | {summary['失败']} |",
        f"| 错误 | {summary['错误']} |",
        f"| 跳过 | {summary['跳过']} |",
        f"| 通过率 | {summary['通过率']} |",
        "",
        "## 测试类明细",
        "",
        "| 测试类 | 总数 | 通过 | 失败 | 跳过 | 耗时(s) |",
        "|--------|------|------|------|------|---------|",
    ]

    for s in report["测试类明细"]:
        failed = s["用例统计"]["失败"] + s["用例统计"]["错误"]
        status = "✅" if s["用例统计"]["通过"] == s["用例统计"]["总数"] else ("❌" if failed > 0 else "⚠️")
        lines.append(
            f"| {status} {_md_escape(s['类名'])} | {s['用例统计']['总数']} | "
            f"{s['用例统计']['通过']} | {failed} | {s['用例统计']['跳过']} | {s['耗时秒']:.3f} |"
        )

        for c in s["用例明细"]:
            st_emoji = {"passed": "✅", "failure": "❌", "error": "⚠️", "skipped": "➡️"}.get(c["状态"], "")
            lines.append(f"| &nbsp;&nbsp;&nbsp;&nbsp;{st_emoji} {_md_escape(c['方法名'])} | | | | | {c['耗时秒']:.3f}s |")
            if c["状态"] in ("error", "failure"):
                msg = c.get("错误信息", "")[:200]
                lines.append(f"| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;> {_md_escape(msg)} | | | | | |")
            if c["状态"] == "skipped":
                reason = c.get("跳过原因", "")
                lines.append(f"| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;> 原因: {_md_escape(reason)} | | | | | |")

    lines += [
        "",
        "## 环境信息",
        "",
        "| 项目 | 值 |",
        "|------|-----|",
    ]
    for k, v in env.items():
        lines.append(f"| {k} | {_md_escape(str(v))} |")

    return "\n".join(lines) + "\n"


def _html_escape(text):
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def _md_escape(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def _v1_html_summary(v1):
    if not v1:
        return ""
    profile = v1.get("capability_profile") or {}
    rows = [
        ("Target Outcome", v1.get("target_outcome", "")),
        ("Run Kind", v1.get("run_kind", "")),
        ("Compatibility Baseline Version", v1.get("compatibility_baseline_version", "")),
        ("Report Schema Version", v1.get("report_schema_version", "")),
        ("Capability Profile", profile.get("completeness", "N/A")),
    ]
    summary_rows = "".join(
        f"<tr><td><b>{_html_escape(k)}</b></td><td>{_html_escape(v)}</td></tr>"
        for k, v in rows
    )
    scenario_rows = "".join(
        "<tr>"
        f"<td>{_html_escape(scenario_id)}</td>"
        f"<td>{_html_escape(result.get('category', ''))}</td>"
        f"<td>{_html_escape(result.get('compatibility_status', ''))}</td>"
        f"<td>{_html_escape(result.get('source_class', '').split('.')[-1] + '.' + result.get('source_method', ''))}</td>"
        "</tr>"
        for scenario_id, result in v1.get("scenario_results", {}).items()
    )
    return f"""
<h2>v1 兼容性评估</h2>
<table>
<tr><th style="width:260px">项目</th><th>值</th></tr>
{summary_rows}
</table>
<h2>v1 场景结果</h2>
<table>
<tr><th>Scenario ID</th><th>Category</th><th>Compatibility Status</th><th>Source</th></tr>
{scenario_rows}
</table>
"""


def archive_report(config, report):
    """归档报告到 report/{db_type}_{timestamp}/"""
    db_type = config.get("db", {}).get("type", "unknown")
    timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    output_dir = config.get("report", {}).get("output_dir", "report")
    report_dir = Path(output_dir)
    if not report_dir.is_absolute():
        report_dir = PROJECT_ROOT / report_dir
    report_dir = report_dir / f"{db_type}_{timestamp}"
    report_dir.mkdir(parents=True, exist_ok=True)

    formats = config.get("report", {}).get("format", ["json"])

    if "json" in formats:
        json_path = report_dir / "report.json"
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"[归档] JSON -> {json_path}")

        v1_report = report.get("v1_compatibility_report")
        if v1_report:
            v1_path = report_dir / "compatibility-report-v1.json"
            with open(v1_path, "w", encoding="utf-8") as f:
                json.dump(v1_report, f, ensure_ascii=False, indent=2)
            print(f"[归档] v1 JSON -> {v1_path}")

            matrix_path = report_dir / "compatibility-matrix.json"
            with open(matrix_path, "w", encoding="utf-8") as f:
                json.dump(generate_matrix([v1_report]), f, ensure_ascii=False, indent=2)
            print(f"[归档] Matrix -> {matrix_path}")

            vendor_path = report_dir / "vendor-extension-report.json"
            with open(vendor_path, "w", encoding="utf-8") as f:
                json.dump(vendor_extension_report([v1_report]), f, ensure_ascii=False, indent=2)
            print(f"[归档] Vendor Extensions -> {vendor_path}")

    if "html" in formats:
        html_path = report_dir / "report.html"
        with open(html_path, "w", encoding="utf-8") as f:
            f.write(generate_html_report(report))
        print(f"[归档] HTML -> {html_path}")

    if "markdown" in formats:
        md_path = report_dir / "report.md"
        with open(md_path, "w", encoding="utf-8") as f:
            f.write(generate_markdown_report(report))
        print(f"[归档] MD   -> {md_path}")

    return str(report_dir)


def main():
    config_path = sys.argv[1] if len(sys.argv) > 1 else None

    print("=" * 60)
    print("JDBC Test Runner")
    print("=" * 60)

    # 1. 加载配置
    print("[1/5] 加载配置...")
    config = load_config(config_path)
    db = config.get("db", {})
    print(f"  数据库类型: {db.get('type')}")
    print(f"  JDBC URL: {db.get('url', '')}")

    # 2. 生成 junit-platform.properties
    print("[2/5] 生成 JUnit 平台配置...")
    generate_junit_properties(config)

    # 3. 执行测试
    print("[3/5] 执行测试...")
    exec_mode = config.get("execution", {}).get("mode", "local")
    if exec_mode == "docker":
        execution = run_docker_test(config)
    else:
        execution = run_maven_test(config)

    # 4. 解析报告
    print("[4/5] 解析测试报告...")
    test_suites, env_info = parse_surefire_reports()

    # 5. 生成报告并归档
    print("[5/5] 生成报告...")
    report = generate_json_report(config, execution, test_suites, env_info)
    report["v1_compatibility_report"] = build_v1_report(config, execution, test_suites, env_info, PROJECT_ROOT)
    archive_path = archive_report(config, report)

    print("=" * 60)
    summary = report["测试汇总"]
    print(f"  测试类: {summary['测试类数量']}")
    print(f"  测试用例: {summary['测试方法总数']}")
    print(f"  通过: {summary['通过']} | 失败: {summary['失败']} | 错误: {summary['错误']} | 跳过: {summary['跳过']}")
    print(f"  通过率: {summary['通过率']}")
    print(f"  Target Outcome: {report['v1_compatibility_report']['target_outcome']}")
    print(f"  总耗时: {summary.get('总耗时秒', execution['elapsed_seconds'])}s")
    print(f"  报告目录: {archive_path}")
    print("=" * 60)

    return 0 if execution["exit_code"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
