import React, { useEffect, useRef, useState } from 'react';
import { History24Regular } from '@fluentui/react-icons';

const kinds = { shell: 'Shell 命令', exec: '执行请求', app: '应用管理', sync: '文件通道', push: '上传文件', pull: '下载文件', file: '文件查询', service: 'ADB 服务', unparsed: '记录不完整' };

export function CommandHistory({ history, stale, disabled, run, busy }) {
  const [older, setOlder] = useState(null);
  const [confirm, setConfirm] = useState(false);
  const epoch = useRef(history?.epoch);
  epoch.current = history?.epoch;
  useEffect(() => { setOlder(null); setConfirm(false); }, [history?.epoch]);
  if (!history) return null;
  const data = older || history;
  const entries = data.entries || [];
  const loadOlder = async () => {
    const stamp = history.epoch;
    const result = await run('history', { before: entries.at(-1).id, epoch: stamp });
    if (result && epoch.current === stamp && result.epoch === stamp) setOlder(result);
  };
  return <>
    <div className="section-heading"><h2>远程 ADB 历史记录</h2><span>保留最近 {history.limit} 条</span></div>
    <section className="group command-history" aria-label="远程 ADB 历史记录">
      <div className="history-toolbar"><span>{older ? '较早记录' : stale ? '上次记录 · 待同步' : '最新请求 · 自动同步'} · 共 {history.count} 条</span><button className="text-button danger" disabled={disabled || (!history.count && !history.problem)} onClick={() => setConfirm(true)}>清空</button></div>
      <p className="history-note">记录通过本模块发给手机的请求，不代表执行成功。命令参数可能含隐私，仅保存在手机本地。</p>
      {history.problem && <p className="field-error history-note" role="status">{history.problem}</p>}
      {confirm && <div className="confirm-box history-confirm"><p>清空这台手机保存的全部 ADB 历史记录？后续新请求仍会记录。</p><div className="button-row"><button className="secondary-button" disabled={!!busy} onClick={() => setConfirm(false)}>取消</button><button className="danger-button" disabled={disabled} onClick={async () => { if (await run('clear-history', {}, '历史记录已清空。')) { setOlder(null); setConfirm(false); } }}>{busy === 'clear-history' ? '正在清空…' : '确认清空'}</button></div></div>}
      {entries.length ? <ol className="history-list" key={older ? entries[0].id : 'latest'} tabIndex="0" aria-label="请求列表，可滚动">{entries.map(item => <li key={item.id}><div className="history-meta"><strong>{kinds[item.kind] || 'ADB 请求'}</strong><time dateTime={new Date(item.at).toISOString()}>{new Date(item.at).toLocaleString('zh-CN', { hour12: false })}</time></div><code dir="auto">{item.command}</code><span className="history-peer">来源：{item.peer || '未知'} · {item.kind === 'unparsed' ? '记录提示' : '已转发请求'}</span></li>)}</ol> : <div className="empty-state"><History24Regular /><strong>{older ? '没有更早的记录' : '暂无 ADB 请求记录'}</strong><p>远程电脑发起调试、安装或传输后会显示在这里。</p></div>}
      <div className="history-footer"><button className="secondary-button" disabled={!older || !!busy} onClick={() => setOlder(null)}>返回最新</button><button className="secondary-button" disabled={disabled || !data.hasMore || !entries.length} onClick={loadOlder}>{busy === 'history' ? '正在读取…' : '查看更早记录'}</button></div>
      <p className="history-note">可查看 shell / exec、应用管理及文件路径；长命令会截断。交互式 shell 只记录打开会话，不记录逐行输入；电脑本地命令（如 adb devices）不会传到手机。</p>
    </section>
  </>;
}
