import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, mockApi, problem, renderPage } from "@/test/harness";
import { connection, finishedRun, page } from "@/test/fixtures";
import { request } from "@/api/client";
import i18n from "@/i18n";

function deferred<T>() { let resolve!: (value:T)=>void; const promise=new Promise<T>(r=>{resolve=r;}); return {promise,resolve}; }
afterEach(()=>document.querySelector('meta[name="jdbc-environment"]')?.remove());
describe("Resource loading and preserved history",()=>{
 it("shows the initial title and navigation while data is slow, then renders real data",async()=>{
  const pending=deferred<Response>();mockApi(url=>url.pathname==='/api/runs'?pending.promise:undefined);renderPage('/runs');
  expect(screen.getByRole('heading',{name:'Run history'})).toBeVisible();
  expect(screen.getByRole('navigation',{name:'Main navigation'})).toBeVisible();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
  expect(screen.getByLabelText('Loading data…')).toBeVisible();
  pending.resolve(json(page([finishedRun])));expect(await screen.findByRole('table')).toHaveTextContent(connection.name);
 });
 it("keeps exact-query cached rows through return, refresh and a refresh failure",async()=>{
  let next:Promise<Response>|undefined;mockApi(url=>url.pathname==='/api/runs'?next:undefined);
  const {router,client}=renderPage('/runs');await screen.findByRole('table');
  await act(()=>router.navigate('/drivers'));await screen.findByRole('heading',{name:'Driver packages'});
  const pending=deferred<Response>();next=pending.promise;
  await act(()=>router.navigate('/runs'));expect(screen.getByRole('table')).toHaveTextContent(connection.name);
  expect(screen.queryByLabelText('Loading data…')).not.toBeInTheDocument();
  pending.resolve(problem('HTTP_ERROR',503));await screen.findByRole('alert');
  expect(screen.getByRole('table')).toHaveTextContent(connection.name);
  next=undefined;await act(()=>client.invalidateQueries({queryKey:['runs']}));
 });
 it("does not display the previous filter's rows as the new filter result",async()=>{
  const pending=deferred<Response>();mockApi(url=>url.pathname==='/api/runs'&&url.searchParams.get('status')==='FAILED'?pending.promise:undefined);renderPage('/runs');
  await screen.findByRole('table');const user=userEvent.setup();await user.click(screen.getByRole('combobox',{name:'Filter run status'}));await user.click(screen.getByRole('option',{name:'Run failed'}));
  expect(screen.queryByRole('table')).not.toBeInTheDocument();expect(screen.getByLabelText('Loading data…')).toBeVisible();
  pending.resolve(json(page([])));expect(await screen.findByText('No runs match this status.')).toBeVisible();
 });
 it("does not gate the connection step on catalog, and cancels obsolete reads on navigation",async()=>{
  let signal:AbortSignal|null|undefined;
  mockApi((url,options)=>url.pathname==='/api/test-catalog'?new Promise<Response>((_,reject)=>{signal=options.signal;signal?.addEventListener('abort',()=>reject(signal?.reason));}):undefined);
  const {router}=renderPage('/runs/new');expect(await screen.findByRole('radio',{name:connection.name})).toBeVisible();
  await userEvent.setup().click(screen.getByRole('radio',{name:connection.name}));
  await waitFor(()=>expect(signal).toBeDefined());
  await act(()=>router.navigate('/drivers'));await screen.findByRole('heading',{name:'Driver packages'});await waitFor(()=>expect(signal?.aborted).toBe(true));
 });
 it("keeps the title and offers retry after a cold read fails",async()=>{
  mockApi(url=>url.pathname==='/api/runs'?problem('HTTP_ERROR',503):undefined);renderPage('/runs');
  expect(await screen.findByRole('alert')).toBeVisible();expect(screen.getByRole('heading',{name:'Run history'})).toBeVisible();expect(screen.getByRole('button',{name:'Try again'})).toBeEnabled();
 });
 it("surfaces read timeouts and preserves cancellation semantics",async()=>{
  const timer=new AbortController();vi.spyOn(AbortSignal,'timeout').mockReturnValue(timer.signal);
  vi.spyOn(globalThis,'fetch').mockImplementation((_,options)=>new Promise((_,reject)=>options?.signal?.addEventListener('abort',()=>reject(options.signal?.reason))));
  const pending=request('/runs');timer.abort(new DOMException('timeout','TimeoutError'));
  await expect(pending).rejects.toMatchObject({code:'REQUEST_TIMEOUT'});
 });
 it("places the module disclosure inside overview after tabs and exposes all modules",async()=>{
  const modules=['connection','type_mapping','fixture_long_module_name_which_is_not_a_real_history_record','fixture_extra'];
  mockApi(url=>url.pathname==='/api/runs/run-1'?json({...finishedRun,selectedModules:modules}):undefined);renderPage('/runs/run-1');
  const overview=await screen.findByRole('tabpanel',{name:'Overview'});const summary=within(overview).getByText('Expand all modules');
  expect(summary.closest('details')).not.toHaveAttribute('open');await userEvent.setup().click(summary);expect(summary.closest('details')).toHaveAttribute('open');expect(within(overview).getByText('fixture_extra')).toBeVisible();await userEvent.setup().click(summary);expect(within(overview).getByText('fixture_extra')).not.toBeVisible();
 });
 it("omits the historical badge while preserving report provenance in the viewer",async()=>{
  const meta=document.createElement('meta');meta.name='jdbc-environment';meta.content='history-copy';document.head.append(meta);
  mockApi(url=>url.pathname.endsWith('/report.json')?json({source:'PERSISTED_WORKER_EVENTS'}):undefined);renderPage('/runs/run-1');
  expect(screen.queryByText('Historical data copy')).not.toBeInTheDocument();
  await act(()=>i18n.changeLanguage('zh-CN'));expect(screen.queryByText('历史数据副本')).not.toBeInTheDocument();
  await userEvent.setup().click(await screen.findByRole('tab',{name:'报告'}));
  await userEvent.setup().click(await screen.findByRole('button',{name:'查看报告 JSON'}));
  expect(await screen.findByText(/数据来源：数据库中保存的运行事件。/)).toBeVisible();
  expect(screen.getByText(/此历史查看副本的原始 report.json 文件不可用。/)).toBeVisible();
 });
 it("does not label normal deployment as a historical copy",async()=>{mockApi();renderPage('/');await screen.findByRole('table');expect(screen.queryByText('Historical data copy')).not.toBeInTheDocument();});
});
