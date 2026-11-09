import React, { memo, useEffect, useMemo, useRef, useState } from 'react';
import { History24Regular, Search20Regular, Dismiss20Regular, Delete20Regular, Code20Regular, Apps20Regular, Folder20Regular, Info20Regular, Desktop24Regular, ChevronRight20Regular } from '@fluentui/react-icons';
import { HISTORY_PAGE_SIZE, readHistoryPage, historyFilters as filters, historyCategory, historyLabel, historyCommand, historyCommandFamily, historyResultLabel } from './history-model.mjs';

const icons = { command: Code20Regular, app: Apps20Regular, file: Folder20Regular, other: Info20Regular };
const dateFormats = {
  day: new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' }),
  year: new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }),
  time: new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }),
  full: new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }),
};
const emptyEntries = [];
const sameFields = (a, b) => a === b || !!a && !!b && Object.keys(a).length === Object.keys(b).length && Object.keys(a).every(key => a[key] === b[key]);
const sameRecord = (a, b) => a === b || Object.keys(a).length === Object.keys(b).length && Object.keys(a).every(key => key === 'response' ? sameFields(a[key], b[key]) : a[key] === b[key]);

function dateInfo(at) {
  const date = new Date(at);
  if (!Number.isFinite(date.getTime())) return { day: '未知日期', time: '时间未知', full: '时间未知' };
  const today = new Date();
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  const key = date.toDateString();
  const day = key === today.toDateString() ? '今天' : key === yesterday.toDateString() ? '昨天' : (date.getFullYear() === today.getFullYear() ? dateFormats.day : dateFormats.year).format(date);
  return { day, key, time: dateFormats.time.format(date), full: dateFormats.full.format(date), iso: date.toISOString() };
}

const HistoryRecord = memo(function HistoryRecord({ item }) {
  const [expanded, setExpanded] = useState(false);
  const stamp = dateInfo(item.at);
  const Icon = icons[historyCategory(item)] || Info20Regular;
  const command = historyCommand(item);
  const family = historyCommandFamily(item);
  return <li className="history-record">
    <details className="history-record-details" onToggle={event => setExpanded(event.currentTarget.open)}>
      <summary>
        <span className="history-record-icon" aria-hidden="true"><Icon /></span>
        <span className="history-record-summary">
          <span className="history-record-meta"><strong>{family ? family + ' · ' : ''}{historyLabel(item)}</strong><time dateTime={stamp.iso}>{stamp.time}</time></span>
          <code className="history-command-preview" dir="auto">{command}</code>
          <span className="history-result-brief">{historyResultLabel(item)}</span>
          <span className="history-record-source"><Desktop24Regular aria-hidden="true" /><span>{item.peer || '来源未知'}</span><span className="history-detail-hint">详情<ChevronRight20Regular aria-hidden="true" /></span></span>
        </span>
      </summary>
      {expanded && <div className="history-record-body">
        <div className="history-request-status">{item.kind === 'unparsed' ? '记录未完整解析' : historyResultLabel(item)}</div>
        {command !== item.command && <><p>按设备端请求整理的 ADB 等效表达，并非电脑原始命令：</p><code dir="auto">{command}</code></>}
        <p>设备实际收到的请求（敏感字段已隐藏）：</p><code dir="auto">{item.command}</code>
        {family && <p>这是应用操作的一个请求阶段。下方是该阶段的手机返回内容；电脑端 APK 路径、选择设备参数和原始调用方式不可见。</p>}
        {['push', 'pull'].includes(item.kind) && <p>仅可看到手机端路径，电脑端路径不可见；占位文字不可直接执行。</p>}
        <div className="history-result">
          <h4>手机返回</h4>
          {item.response?.stdout && <><p>标准输出／返回文本</p><code dir="auto">{item.response.stdout}</code></>}
          {item.response?.stderr && <><p>错误输出</p><code className="history-result-error" dir="auto">{item.response.stderr}</code></>}
          {!item.response?.stdout && !item.response?.stderr && <p>{!item.response ? '此记录创建时尚未采集返回内容，无法补录。' : item.response.binary ? '检测到二进制返回，不保存内容。' : item.response.state === 'pending' ? '尚未收到可显示的文本。' : '未收到可显示的文本；不代表命令执行成功。'}</p>}
          {item.response?.note && <p>{item.response.note}</p>}
          {item.response?.truncated && <p className="history-output-notice">输出超过 {Math.round((item.response.limit || 4096) / 1024)} KiB，仅保留开头部分，已截断。</p>}
        </div>
        <time dateTime={stamp.iso}>{stamp.full}</time>
      </div>}
    </details>
  </li>;
}, (previous, next) => previous.today === next.today && sameRecord(previous.item, next.item));

const HistoryStream = memo(function HistoryStream({ entries, today }) {
  const groups = useMemo(() => {
    const result = [];
    for (const item of entries) {
      const stamp = dateInfo(item.at);
      const previous = result.at(-1);
      if (previous && previous.key === (stamp.key || stamp.day)) previous.entries.push(item);
      else result.push({ key: stamp.key || stamp.day, title: stamp.day, entries: [item] });
    }
    return result;
  }, [entries, today]);
  return <>{groups.map(group => <div className="history-day" key={group.key}><h3>{group.title}<span>{group.entries.length} 条</span></h3><ol>{group.entries.map(item => <HistoryRecord key={item.id} item={item} today={today} />)}</ol></div>)}</>;
});

export function CommandHistory({ history, stale, disabled, run, busy }) {
  const [older, setOlder] = useState(null);
  const [confirm, setConfirm] = useState(false);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const [applied, setApplied] = useState({ filter: 'all', query: '' });
  const [loading, setLoading] = useState(false);
  const [queryError, setQueryError] = useState('');
  const sequence = useRef(0);
  const observed = useRef('');
  const context = useRef({ history, stale, disabled, busy, run, older, applied, loading });
  context.current = { history, stale, disabled, busy, run, older, applied, loading };
  const epoch = useRef(history?.epoch);
  epoch.current = history?.epoch;
  useEffect(() => { sequence.current++; setLoading(false); setOlder(null); setConfirm(false); setQuery(''); setFilter('all'); setApplied({ filter: 'all', query: '' }); setQueryError(''); observed.current = ''; }, [history?.epoch]);
  useEffect(() => () => { sequence.current++; }, []);
  const load = async (nextFilter, nextQuery, before = 0, seed) => {
    const stamp = epoch.current, request = ++sequence.current;
    const key = `${stamp}|${context.current.history?.revision ?? context.current.history?.entries?.[0]?.id ?? 0}|${nextFilter}|${nextQuery}`;
    observed.current = key; setLoading(true); setQueryError('');
    const active = () => request === sequence.current && epoch.current === stamp;
    try {
      const result = await readHistoryPage(payload => context.current.run('history', payload),
        { before, epoch: stamp, filter: nextFilter, query: nextQuery }, seed, active);
      if (!active()) return;
      if (!result) throw new Error('历史记录读取未完成');
      setOlder({ ...result, before }); setApplied({ filter: nextFilter, query: nextQuery });
    } catch {
      if (active()) setQueryError('记录未完整读取，请重试；下方保留上次确认的记录。');
    } finally {
      if (active()) setLoading(false);
    }
  };
  // Reuse normal status observations as the trigger. No independent polling timer.
  useEffect(() => {
    const current = context.current;
    if (!current.history || current.disabled || current.stale || current.loading || current.older?.before > 0) return;
    const unfiltered = current.applied.filter === 'all' && !current.applied.query;
    if (unfiltered && (!current.history.hasMore || current.history.entries.length >= HISTORY_PAGE_SIZE)) {
      if (current.older) setOlder(null);
      return;
    }
    const key = `${current.history.epoch}|${current.history.revision ?? current.history.entries?.[0]?.id ?? 0}|${current.applied.filter}|${current.applied.query}`;
    if (observed.current !== key) void load(current.applied.filter, current.applied.query, 0, unfiltered ? current.history : undefined);
  }, [history?.epoch, history?.revision, history?.entries?.[0]?.id, disabled, stale, applied.filter, applied.query, older?.before, loading]);
  if (!history) return null;
  const data = older || history;
  const entries = data.entries || emptyEntries;
  const visible = entries;
  const filtered = applied.filter !== 'all' || !!applied.query;
  const resetFilters = () => { sequence.current++; observed.current = ''; setLoading(false); setQuery(''); setFilter('all'); setApplied({ filter: 'all', query: '' }); setOlder(null); setQueryError(''); };
  const applyFilter = next => { setFilter(next); if (next === 'all' && !query.trim()) resetFilters(); else void load(next, query.trim()); };
  const loadOlder = () => load(applied.filter, applied.query, entries.at(-1).id);
  return <section className="command-history" aria-labelledby="history-title">
    <div className="history-heading">
      <span className="history-heading-icon" aria-hidden="true"><History24Regular /></span>
      <div><h2 id="history-title">ADB 历史记录</h2><p>本机保留 {history.count} 条 · 最多 {history.limit} 条</p></div>
      <button className="history-clear text-button" disabled={disabled || (!history.count && !history.problem)} onClick={() => setConfirm(true)}><Delete20Regular aria-hidden="true" />清空</button>
    </div>
    <div className="group history-controls">
      <form className="history-search" role="search" aria-label="历史记录筛选" onSubmit={event => { event.preventDefault(); applyFilter(filter); }}><Search20Regular aria-hidden="true" /><input type="search" aria-label="搜索全部历史的命令、返回内容或来源" placeholder="搜索命令、返回内容、来源" maxLength={128} autoComplete="off" spellCheck="false" value={query} onChange={event => setQuery(event.target.value)} />{query && <button type="button" className="icon-button" aria-label="清除历史搜索" onClick={resetFilters}><Dismiss20Regular /></button>}<button type="submit" className="text-button" disabled={disabled || loading}>搜索</button></form>
      <div className="history-filters" role="group" aria-label="按请求类型筛选">{filters.map(item => <button key={item.id} disabled={disabled || loading} aria-pressed={applied.filter === item.id} onClick={() => applyFilter(item.id)}>{item.label}</button>)}</div>
    </div>
    <div className="history-view-status"><span className={stale || queryError ? 'is-stale' : ''}><i aria-hidden="true" />{loading ? '正在查询全部历史' : stale || queryError ? '上次记录 · 待同步' : older?.before > 0 ? '正在查看较早记录' : '最新记录 · 自动同步'}</span><span>{filtered ? '共匹配 ' + (data.matchedCount ?? entries.length) + ' 条 · 本页 ' + entries.length + ' 条' : '当前页 ' + entries.length + ' 条'}</span></div>
    <p className="history-scope-note">仅保存在手机 · 请求和手机返回对应记录；没有退出码时不推断成功。{filtered && `当前筛选：${filters.find(item => item.id === applied.filter)?.label}${applied.query ? ' · ' + applied.query : ''}；范围为全部已保存记录。`}</p>
    {queryError && <div className="message error" role="status"><Info20Regular /><p>{queryError}</p><button className="text-button" disabled={disabled || loading} onClick={() => load(filter, query.trim())}>重试</button></div>}
    {history.problem && <div className="message error" role="status"><Info20Regular /><p>{history.problem}</p></div>}
    {confirm && <div className="history-confirm" role="group" aria-labelledby="history-clear-title"><span className="history-confirm-icon"><Delete20Regular /></span><div><h3 id="history-clear-title">清空全部历史记录？</h3><p>将删除这台手机保存的 {history.count} 条记录，无法恢复。新请求仍会继续记录。</p></div><div className="button-row"><button className="secondary-button" disabled={!!busy} onClick={() => setConfirm(false)}>保留记录</button><button className="danger-button" disabled={disabled} onClick={async () => { if (await run('clear-history', {}, '历史记录已清空。')) { setOlder(null); setConfirm(false); resetFilters(); } }}>{busy === 'clear-history' ? '正在清空…' : '确认清空'}</button></div></div>}
    {visible.length ? <div className="history-stream" tabIndex="0" role="region" aria-label="ADB 请求列表，可滚动" key={[history.epoch, applied.filter, applied.query, older?.before || 0].join('|')}><HistoryStream entries={entries} today={new Date().toDateString()} /></div> : <div className="group history-empty empty-state"><span className="history-empty-icon">{filtered ? <Search20Regular /> : <History24Regular />}</span><strong>{filtered ? '全部历史中没有匹配记录' : older ? '没有更早的记录' : '还没有远程请求'}</strong><p>{filtered ? '试试其他关键词或类型。' : '电脑通过本模块调试、安装应用或传输文件后，请求会显示在这里。'}</p>{filtered && <button className="text-button" onClick={resetFilters}>重置筛选</button>}</div>}
    <div className="history-footer"><button className="secondary-button" disabled={!older?.before || !!busy || loading} onClick={() => filtered ? load(applied.filter, applied.query) : resetFilters()}>返回最新</button><button className="secondary-button" disabled={disabled || loading || !data.hasMore || !entries.length} onClick={loadOlder}>{loading ? '正在读取…' : '查看更早记录'}<ChevronRight20Regular aria-hidden="true" /></button></div>
    <details className="history-explainer"><summary><Info20Regular aria-hidden="true" /><span>记录范围与隐私</span><ChevronRight20Regular aria-hidden="true" /></summary><p>最多保留最近 {history.limit} 条，每页默认显示 {HISTORY_PAGE_SIZE} 条；超出保存上限后才移除最旧记录。记录可观察的命令参数和手机返回文本；Shell v2／ABB 分开显示标准输出、错误输出及退出码，文件操作保存协议结果，不保存传输的文件内容。单条请求最多读取 4096 字节，结果合计最多 4 KiB，超长内容明确标记截断。安装的各阶段分别记录。设备端无法获得电脑原始命令行、APK 本地路径、-s 等客户端参数，不能保证等效表达可直接重放。交互式 Shell 不采集逐键输入与回显；二进制结果、RSA 认证和未经过模块的命令不采集。旧记录的返回内容无法补录。常见密码、令牌和配对码会脱敏，返回文本仍可能包含个人信息，可随时清空。</p></details>
  </section>;
}
