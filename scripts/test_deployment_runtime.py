#!/usr/bin/env python3
"""First-install acceptance against UUID-named temporary PLATFORM databases only.

Build-machine Python/Java/Docker are required, not end-user host dependencies.
Never runs target probes/tests, DROP, password reset or volume deletion.
Created containers are stopped; private configuration/data are retained.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import threading
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path
from build_distribution import PROJECT_ROOT, project_version

PASSWORD = "  fixture$#'\"\\= \\' ${NAME} \\${NAME} double$$ 中文é🔑  "
SAVED_PASSWORD = "saved credential $ # ' \" \\ = ${NAME} 中文"


def run(*command, expected=0, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, **kwargs)
    if result.returncode != expected:
        # Never dump arguments/output: errors may embed secrets.
        raise RuntimeError(f"Acceptance command {command[0]} exited {result.returncode}; expected {expected}")
    return result.stdout.strip()


def unused_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def request(base, path, data=None, content_type='application/json'):
    body = json.dumps(data).encode() if data is not None and not isinstance(data, bytes) else data
    operation = urllib.request.Request(base + path, data=body, headers={'Content-Type': content_type})
    with urllib.request.urlopen(operation, timeout=40) as response:
        return json.load(response)


def wait_for_health(base, process=None):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError('Native Server exited before becoming healthy; private log retained')
        try:
            if request(base, '/actuator/health')['status'] == 'UP':
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.5)
    raise RuntimeError('Health did not become UP; private diagnostics retained')


def save_credential(base, driver_jar):
    smoke = subprocess.run(['sh', str(PROJECT_ROOT / 'scripts/docker-smoke.sh')],
        env={**os.environ, 'JDBC_TEST_BASE_URL': base}, capture_output=True, text=True)
    if smoke.returncode:
        # Only forward the smoke script's controlled stage messages, never response bodies.
        stage = next((line for line in smoke.stderr.splitlines()
                      if line.startswith('Production smoke failed: ')), 'Production smoke failed')
        raise RuntimeError(f'{stage} (exit {smoke.returncode})')
    boundary = uuid.uuid4().hex
    metadata = json.dumps({'name': 'onboarding-fixture', 'vendor': 'PostgreSQL', 'version': 'fixture', 'driverClass': 'org.postgresql.Driver', 'urlPrefix': 'jdbc:postgresql:'}).encode()
    payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode() + metadata
               + f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="postgresql.jar"\r\nContent-Type: application/java-archive\r\n\r\n'.encode()
               + driver_jar + f'\r\n--{boundary}--\r\n'.encode())
    driver = request(base, '/api/drivers', payload, f'multipart/form-data; boundary={boundary}')
    assert request(base, f"/api/drivers/{driver['id']}/validate", {})['status'] == 'VALIDATED'
    # Save only. No target probe or JDBC test is called.
    connection = request(base, '/api/connections', {'name': 'onboarding-credential', 'databaseType': 'postgresql', 'adapterId': 'postgresql',
        'jdbcUrl': 'jdbc:postgresql://example.invalid:1/never_contacted', 'username': 'fixture', 'password': SAVED_PASSWORD,
        'savePassword': True, 'defaultDriverId': driver['id']})
    assert connection['hasSavedPassword']
    return connection['id']


def private_file(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8', newline='') as stream:
        stream.write(content)


def fingerprint(path):
    return hashlib.sha256(path.read_bytes()).digest()


def copy_example(root, url, username, password, port, web_port=None):
    """Actually copy the public example, then edit the three actual .env fields."""
    target = root / '.env'
    if target.exists():
        raise RuntimeError('Fixture config already exists; refusing overwrite')
    shutil.copyfile(root / '.env.example', target)
    changes = {'SPRING_DATASOURCE_URL': url, 'SPRING_DATASOURCE_USERNAME': username,
               'SPRING_DATASOURCE_PASSWORD': password, 'SERVER_PORT': str(port)}
    # JSON framing is part of the documented literal format, not shell expansion.
    lines = target.read_text().splitlines()
    target.write_text('\n'.join(name + '=' + json.dumps(changes[name], ensure_ascii=False)
                              if (name := line.split('=', 1)[0]) in changes else line
                              for line in lines) + '\n', encoding='utf-8')
    if web_port:
        with target.open('a') as stream:
            stream.write(f'JDBC_TEST_WEB_PORT={web_port}\n')
    # Deliberately leave normal copied-file permissions: the entry must protect it.
    target.chmod(0o644)
    return target


def values(file):
    result = {}
    for line in file.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        result[key] = json.loads(value) if value.startswith('"') else value
    return result


class TerminalLaunch:
    """A real terminal: answer only the actual NEW prompt and send actual Ctrl+C."""
    def __init__(self, command, root, environment, log, replies=None):
        import pty
        self.pid, self.terminal = pty.fork()
        if self.pid == 0:
            os.chdir(root)
            os.execvpe(command[0], command, environment)
        self.log = log.open('ab')
        log.chmod(0o600)
        self.replies = list(replies or [])
        self.pending = bytearray()
        self.transcript = bytearray()
        self.status = None
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def _read_loop(self):
        while self.status is None:
            self._pump_once()

    def pump(self):
        time.sleep(.05)

    def _pump_once(self):
        import select
        if self.status is not None:
            return
        if select.select([self.terminal], [], [], 0.2)[0]:
            try:
                data = os.read(self.terminal, 65536)
            except OSError:
                data = b''
            self.log.write(data)
            self.log.flush()
            self.pending.extend(data)
            self.transcript.extend(data)
            if self.replies and self.replies[0][0] in self.pending:
                _, reply = self.replies.pop(0)
                self.pending.clear()
                os.write(self.terminal, reply.encode() + b'\n')
        pid, status = os.waitpid(self.pid, os.WNOHANG)
        if pid:
            self.status = os.waitstatus_to_exitcode(status)

    def ready(self, base, website=None, timeout=240, banner=True):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.pump()
            if self.status is not None:
                raise RuntimeError('Unified entry exited before readiness; private log retained')
            if banner and b'Ready. Backend:' not in self.transcript:
                continue
            try:
                assert request(base, '/actuator/health')['status'] == 'UP'
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                continue
            assert request(base, '/api/test-catalog')
            if website:
                assert request(website, '/api/test-catalog')
                with urllib.request.urlopen(website, timeout=10) as response:
                    assert response.status == 200 and b'<html' in response.read().lower()
            return
        raise RuntimeError('Unified entry readiness timed out; private log retained')

    def stop(self):
        if self.status is None:
            os.write(self.terminal, b'\x03')
            deadline = time.monotonic() + 45
            while self.status is None and time.monotonic() < deadline:
                self.pump()
            if self.status is None:
                os.kill(self.pid, signal.SIGTERM)
                raise RuntimeError('Ctrl+C did not finish the owned launch')
        self.reader.join(timeout=2)
        self.log.close()
        os.close(self.terminal)


def assert_ports_released(*ports):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        busy = False
        for port in ports:
            with socket.socket() as listener:
                try:
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    listener.bind(('127.0.0.1', port))
                except OSError:
                    busy = True
        if not busy:
            return
        time.sleep(.2)
    raise RuntimeError('Owned Server/Vite still owns a port after Ctrl+C')


def source_copy(destination):
    # Explicit public build inputs. Never copy private .env/.local/QA/drivers/data.
    for name in ('pom.xml', 'mvnw', 'mvnw.cmd', 'jdbc-test', 'jdbc-test.ps1', '.env.example',
                 'LICENSE', 'README.md', 'README.zh-CN.md', 'Dockerfile', '.dockerignore'):
        shutil.copy2(PROJECT_ROOT / name, destination / name)
    for name in ('docker-compose.yml', 'docker-compose.app.yml', 'docker-compose.external.yml', 'docker-compose.host-gateway.yml'):
        shutil.copy2(PROJECT_ROOT / name, destination / name)
    for name in ('.mvn', 'jdbc-test-core', 'jdbc-test-suite', 'jdbc-test-worker', 'jdbc-test-server',
                 'jdbc-test-web', 'deploy', 'scripts', 'docs', 'compatibility', 'adapters', 'ddl', 'dml', 'pool'):
        shutil.copytree(PROJECT_ROOT / name, destination / name,
            ignore=shutil.ignore_patterns('target', 'node_modules', 'dist', '__pycache__', '.env', '.env.local', '*.log', '*.input'))


def check(image, distribution, source_dev=False, build=False, native_only=False):
    identity = f'jdbc-entry-{uuid.uuid4().hex[:10]}'
    workspace = Path(tempfile.mkdtemp(prefix=identity + '-')).resolve()
    workspace.chmod(0o700)
    print(f'Acceptance workspace: {workspace} (private; retained)', flush=True)
    network, pg = identity + '-net', identity + '-pg'
    projects = []
    launch = None
    environment = {key: value for key, value in os.environ.items() if not key.startswith(
        ('POSTGRES_', 'SPRING_', 'JDBC_TEST_', 'JDBC_INIT_', 'COMPOSE_', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'))}
    environment.update({'JDBC_TEST_SETUP_IMAGE': image, 'JDBC_TEST_SETUP_NETWORK': network, 'NAME': 'MUST_NOT_EXPAND'})

    def sql(database, statement, container=pg, port=55432, user='postgres'):
        return run('docker', 'exec', '-i', container, 'psql', '-p', str(port), '-U', user, '-d', database,
                   '-At', '-v', 'ON_ERROR_STOP=1', input=statement, env=environment)

    def readback(bundle, key, envelope):
        run('java', f'-Dloader.path={PROJECT_ROOT / "jdbc-test-server/target/test-classes"}',
            '-Dloader.main=com.jdbctest.server.deploy.CredentialReadbackProbe', '-cp', str(bundle / 'server/jdbc-test-server.jar'),
            'org.springframework.boot.loader.launch.PropertiesLauncher', '--logging.level.root=OFF',
            env={**environment, 'JDBC_TEST_MASTER_KEY': key}, input=json.dumps({'expected': SAVED_PASSWORD, 'envelope': envelope}), cwd=bundle)

    try:
        run('docker', 'network', 'create', network)
        private_file(workspace / 'pg-admin.env', 'POSTGRES_PASSWORD=' + os.urandom(32).hex() + '\n')
        run('docker', 'run', '-d', '--name', pg, '--network', network, '--network-alias', 'custom-platform-pg',
            '--env-file', str(workspace / 'pg-admin.env'), '-p', '127.0.0.1::55432', 'postgres:16', '-p', '55432', env=environment)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if subprocess.run(['docker', 'exec', pg, 'pg_isready', '-p', '55432', '-U', 'postgres'], capture_output=True).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Temporary platform PostgreSQL did not become ready')
        sql('postgres', "SET standard_conforming_strings=on;\nCREATE ROLE fixture_user LOGIN PASSWORD '" + PASSWORD.replace("'", "''")
            + "';\n" + '\n'.join(f'CREATE DATABASE {name} OWNER fixture_user;' for name in ('source_platform', 'bundle_platform', 'external_platform', 'legacy_file', 'legacy_env')))
        pg_port = json.loads(run('docker', 'inspect', '--format', '{{json .NetworkSettings.Ports}}', pg))['55432/tcp'][0]['HostPort']

        if source_dev or build:
            source = workspace / 'source'
            source.mkdir()
            source_copy(source)
            before = time.monotonic()
            message = run('sh', './jdbc-test', 'dev', cwd=source, env=environment, expected=1)
            assert not (source / '.env').exists() and not (source / 'target').exists()
            assert time.monotonic() - before < 5
            print('PASS: missing .env fails before Java build and creates no config', flush=True)
            if source_dev:
                backend, frontend = unused_port(), unused_port()
                config = copy_example(source, f'jdbc:postgresql://127.0.0.1:{pg_port}/source_platform', 'fixture_user', PASSWORD, backend, frontend)
                launch = TerminalLaunch(['sh', './jdbc-test', 'dev'], source, environment, workspace / 'dev.log',
                    [(b'For a confirmed NEW empty platform type NEW;', 'NEW')])
                launch.ready(f'http://127.0.0.1:{backend}', f'http://127.0.0.1:{frontend}', timeout=600)
                original = fingerprint(config)
                key = values(config)['JDBC_TEST_MASTER_KEY']
                assert len(base64.b64decode(key)) == 32 and values(config)['SPRING_DATASOURCE_PASSWORD'] == PASSWORD
                assert config.stat().st_mode & 0o777 == 0o600
                launch.stop(); launch = None
                assert_ports_released(backend, frontend)
                launch = TerminalLaunch(['sh', './jdbc-test', 'dev'], source, environment, workspace / 'dev-repeat.log')
                launch.ready(f'http://127.0.0.1:{backend}', f'http://127.0.0.1:{frontend}', timeout=600)
                assert fingerprint(config) == original and not launch.replies
                launch.stop(); launch = None
                assert_ports_released(backend, frontend)
                assert (source / 'data').is_dir()
                print('PASS: copied example -> edit -> dev builds Server/Worker and starts source Server, Vite, API/Catalog/page; special password, NEW, unchanged Key on repeat, Ctrl+C releases both ports and preserves data', flush=True)
            if build:
                distribution = workspace / 'distribution'
                private_file(workspace / 'build.log', '')
                with (workspace / 'build.log').open('a') as log:
                    result = subprocess.run(['sh', './jdbc-test', 'build', '--output', str(distribution)], cwd=source, env=environment, stdout=log, stderr=subprocess.STDOUT)
                assert result.returncode == 0, 'Unified build failed; private build log retained'
                print('PASS: unified build generates Server/Worker/production frontend and archives', flush=True)

        bundle = distribution / f"jdbc-test-server-{project_version().replace('-SNAPSHOT', '')}"
        with zipfile.ZipFile(bundle / 'server/jdbc-test-server.jar') as jar:
            driver = next(name for name in jar.namelist() if name.startswith('BOOT-INF/lib/postgresql-') and name.endswith('.jar'))
            driver_jar = jar.read(driver)
        native = workspace / 'bundle'
        shutil.copytree(bundle, native)
        port = unused_port()
        config = copy_example(native, f'jdbc:postgresql://127.0.0.1:{pg_port}/bundle_platform', 'fixture_user', PASSWORD, port)
        launch = TerminalLaunch(['sh', './jdbc-test', 'start'], native, environment, workspace / 'bundle.log',
            [(b'For a confirmed NEW empty platform type NEW;', 'NEW')])
        base = f'http://127.0.0.1:{port}'
        launch.ready(base)
        connection_id = save_credential(base, driver_jar)
        original, key = fingerprint(config), values(config)['JDBC_TEST_MASTER_KEY']
        launch.stop(); launch = None
        assert_ports_released(port)
        launch = TerminalLaunch(['sh', './jdbc-test', 'start'], native, environment, workspace / 'bundle-repeat.log')
        launch.ready(base)
        assert fingerprint(config) == original and request(base, f'/api/connections/{connection_id}')['hasSavedPassword']
        readback(native, key, sql('bundle_platform', f"SELECT encrypted_password FROM database_connection_profile WHERE id='{connection_id}';"))
        launch.stop(); launch = None
        assert_ports_released(port)
        # Existing data without its original Key must never receive a replacement.
        current = values(config); current['JDBC_TEST_MASTER_KEY'] = ''
        config.write_text('# jdbc-test literal dotenv v1\n' + ''.join(k+'='+json.dumps(v, ensure_ascii=False)+'\n' for k,v in current.items()))
        lost_key = fingerprint(config)
        run('sh', './jdbc-test', 'start', '--new-install', cwd=native, env=environment, expected=1)
        assert fingerprint(config) == lost_key
        print('PASS: built bundle copy/edit/start; real PG password, saved credential decrypt after restart, original Key and recovery refusal, Ctrl+C keeps data', flush=True)

        # Published historical start entries are only forwards. Verify both old sources.
        for mode in ('file', 'env'):
            legacy = workspace / ('legacy-' + mode)
            shutil.copytree(bundle, legacy)
            legacy_port = unused_port()
            url = f'jdbc:postgresql://127.0.0.1:{pg_port}/legacy_{mode}'
            legacy_key = base64.b64encode(os.urandom(32)).decode()
            legacy_environment = {**environment, 'SERVER_PORT': str(legacy_port)}
            if mode == 'file':
                # Java Properties Unicode/escape framing, actual Spring+PG authentication below.
                def prop(value):
                    encoded = value.encode('utf-16-be')
                    return ''.join('\\u' + encoded[i:i+2].hex() for i in range(0, len(encoded), 2))
                private_file(legacy / 'config/application.properties', '\n'.join(f'{k}={prop(v)}' for k,v in {
                    'spring.datasource.url': url, 'spring.datasource.username': 'fixture_user', 'spring.datasource.password': PASSWORD,
                    'jdbc-test.security.master-key': legacy_key}.items()) + '\n')
            else:
                legacy_environment.update({'SPRING_DATASOURCE_URL': url, 'SPRING_DATASOURCE_USERNAME': 'fixture_user',
                    'SPRING_DATASOURCE_PASSWORD': PASSWORD, 'JDBC_TEST_MASTER_KEY': legacy_key})
            launch = TerminalLaunch(['sh', './start.sh'], legacy, legacy_environment, workspace / ('legacy-' + mode + '.log'))
            launch.ready(f'http://127.0.0.1:{legacy_port}', banner=False)
            assert request(f'http://127.0.0.1:{legacy_port}', '/api/test-catalog')
            launch.stop(); launch = None
            assert_ports_released(legacy_port)
        print('PASS: historical start forwards through unified start with both existing properties and environment configurations', flush=True)

        if not native_only:
            for mode in ('bundled', 'external'):
                root = workspace / ('docker-' + mode)
                root.mkdir()
                for name in ('jdbc-test', 'jdbc-test.ps1', '.env.example'):
                    shutil.copy2(PROJECT_ROOT / name, root / name)
                shutil.copytree(PROJECT_ROOT / 'deploy/docker', root / 'deploy/docker')
                for path in PROJECT_ROOT.glob('docker-compose*.yml'):
                    shutil.copy2(path, root / path.name)
                if mode == 'external':
                    with (root / 'docker-compose.external.yml').open('a') as stream:
                        stream.write(f'networks:\n  default:\n    external: true\n    name: {network}\n')
                # Fixture only: same production runtime, current artifacts; not standard image build evidence.
                (root / 'Dockerfile').write_text(f'FROM {image}\n')
                project, port = identity + '-' + mode, unused_port()
                compose = ['docker', 'compose', '-p', project, '-f', str(root / ('docker-compose.yml' if mode == 'bundled' else 'docker-compose.external.yml'))]
                projects.append(compose)
                if mode == 'external':
                    config = copy_example(root, 'jdbc:postgresql://custom-platform-pg:55432/external_platform', 'fixture_user', PASSWORD, port)
                    config.chmod(0o600)  # helper container checks host privacy, does not change host ownership.
                    with config.open('a') as stream:
                        stream.write('COMPOSE_PROJECT_NAME=' + project + '\n')
                command = ['sh', './jdbc-test', 'docker', '--' + ('bundled-pg' if mode == 'bundled' else 'external-pg'), '--project', project]
                run(*command, '--new-install', '--port', str(port), cwd=root, env=environment)
                config = root / '.env'
                original = fingerprint(config)
                app = run(*compose, 'ps', '-q', 'jdbc-test', cwd=root, env=environment)
                actual = dict(v.split('=', 1) for v in json.loads(run('docker', 'inspect', app))[0]['Config']['Env'])
                if mode == 'bundled':
                    pg_container = run(*compose, 'ps', '-q', 'postgres', cwd=root, env=environment)
                    actual_pg = dict(v.split('=', 1) for v in json.loads(run('docker', 'inspect', pg_container))[0]['Config']['Env'])
                    assert actual_pg['POSTGRES_PASSWORD'] == actual['SPRING_DATASOURCE_PASSWORD']
                    assert len(bytes.fromhex(actual_pg['POSTGRES_PASSWORD'])) == 32
                    assert bytes.fromhex(actual_pg['POSTGRES_PASSWORD']) != base64.b64decode(actual['JDBC_TEST_MASTER_KEY'])
                else:
                    assert actual['SPRING_DATASOURCE_PASSWORD'] == PASSWORD
                wait_for_health(f'http://127.0.0.1:{port}')
                assert request(f'http://127.0.0.1:{port}', '/api/test-catalog')
                run(*command, '--stop', cwd=root, env=environment)
                run(*command, cwd=root, env=environment)
                assert fingerprint(config) == original
                config.rename(root / '.env.retained')
                try:
                    run(*command, '--new-install', cwd=root, env=environment, expected=1)
                    assert not config.exists()
                finally:
                    (root / '.env.retained').rename(config)
                print(f'PASS: unified Docker {mode} init/check/Compose; exact password, real PG authentication, repeat original Key, stop retains volumes and missing-config state guard', flush=True)
    finally:
        if launch is not None:
            launch.stop()
        for compose in projects:
            subprocess.run([*compose, 'stop'], env=environment, capture_output=True)
        subprocess.run(['docker', 'stop', pg], capture_output=True)
        print(f'Stopped only {identity} resources; configs, data, containers and volumes retained. Workspace: {workspace}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='jdbc-test:ci')
    parser.add_argument('--distribution', type=Path, default=PROJECT_ROOT / 'dist')
    parser.add_argument('--source-dev', action='store_true', help='Test actual copied-example source dev including rebuild and Ctrl+C')
    parser.add_argument('--build', action='store_true', help='Test unified build in the private public-source copy')
    parser.add_argument('--native-only', action='store_true', help='Skip Docker launch when the standard image build is blocked')
    arguments = parser.parse_args()
    check(arguments.image, arguments.distribution.resolve(), arguments.source_dev, arguments.build, arguments.native_only)


if __name__ == '__main__':
    main()
