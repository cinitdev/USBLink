export const HISTORY_PAGE_SIZE = 100;

// A native response stays below the control channel's byte budget. Fill one
// visible page across bounded reads without skipping long commands/results.
export async function readHistoryPage(read, payload, seed, active = () => true) {
  const entries = [];
  let cursor = payload.before || 0;
  let page = seed;
  while (active()) {
    if (!page) page = await read({ ...payload, before: cursor });
    if (!active()) return null;
    if (!page || page.epoch !== payload.epoch || (page.filter || 'all') !== payload.filter || (page.query || '') !== payload.query)
      throw new Error('历史记录已更新，请重新读取');
    const batch = page.entries || [];
    const remaining = HISTORY_PAGE_SIZE - entries.length;
    for (const item of batch.slice(0, remaining)) {
      if (!Number.isSafeInteger(item.id) || item.id <= 0 || (cursor && item.id >= cursor))
        throw new Error('历史分页顺序无效，请重新读取');
      entries.push(item); cursor = item.id;
    }
    const hasMore = batch.length > remaining || !!page.hasMore;
    if (entries.length === HISTORY_PAGE_SIZE || !hasMore) return { ...page, entries, hasMore };
    if (!batch.length) throw new Error('历史记录读取不完整，请重试');
    page = null;
  }
  return null;
}

export const historyFilters = [
  ['all', '全部'], ['install', '安装应用'], ['app', '应用管理'], ['command', 'Shell'],
  ['file', '文件'], ['debug', '调试'], ['device', '系统'], ['network', '网络'], ['other', '其他'],
].map(([id, label]) => ({ id, label }));
export const actionLabels = {
  install: '安装应用', 'install-create': '创建安装会话', 'install-write': '写入安装包',
  'install-commit': '提交安装', 'install-abandon': '放弃安装', 'install-add-session': '关联安装会话',
  'install-incremental': '增量安装', 'install-streaming': '流式安装', 'install-existing': '启用已存在应用',
  'install-other': '安装管理', uninstall: '卸载应用', 'app-clear': '清除应用数据',
  'app-permission': '应用权限', 'app-control': '启动／控制应用', 'package-command': '应用管理',
  'binder-command': '系统服务调用', shell: 'Shell 命令', 'interactive-shell': '打开交互式 Shell',
  push: '上传文件', pull: '下载文件', 'file-query': '文件查询', 'file-command': '文件命令',
  sync: '打开文件通道', backup: '备份请求', restore: '恢复请求', logcat: '读取日志',
  bugreport: '生成错误报告', screencap: '截取屏幕', screenrecord: '录制屏幕', diagnostic: '诊断命令',
  'debug-service': '调试服务', 'system-command': '系统命令', reboot: '重启设备', root: '请求 adbd Root',
  unroot: '请求普通 adbd', remount: '重新挂载', 'disable-verity': '请求关闭校验', 'enable-verity': '请求开启校验',
  transport: '连接／转发服务', 'network-command': '网络命令', service: 'ADB 服务', unparsed: '记录不完整',
};
const kindLabels = { shell: 'Shell 命令', exec: '执行请求', app: '应用管理', sync: '文件通道', push: '上传文件', pull: '下载文件', file: '文件查询', unparsed: '记录不完整' };
export const historyCategory = item => item.category || (['shell', 'exec'].includes(item.kind) ? 'command' : item.kind === 'app' ? 'app' : ['sync', 'push', 'pull', 'file'].includes(item.kind) ? 'file' : 'other');
export const historyLabel = item => actionLabels[item.action] || kindLabels[item.kind] || 'ADB 请求';
export function historyCommand(item) {
  if (item.adbCommand) return item.adbCommand;
  const raw = String(item.command || '');
  const quote = value => /^[A-Za-z0-9_./:=,+@%-]+$/.test(value) ? value : "'" + value.replaceAll("'", "'\"'\"'") + "'";
  if (item.kind === 'push') return 'adb push <电脑端源路径未知> ' + quote(raw.slice(raw.indexOf(' ') + 1));
  if (item.kind === 'pull') return 'adb pull ' + quote(raw.slice(raw.indexOf(' ') + 1)) + ' <电脑端目标路径未知>';
  const colon = raw.indexOf(':');
  const service = (colon < 0 ? raw : raw.slice(0, colon)).split(',')[0];
  const payload = colon < 0 ? '' : raw.slice(colon + 1);
  if (service === 'shell') return 'adb shell' + (payload ? ' ' + payload : '');
  if (service === 'exec') return 'adb exec-out ' + payload;
  if (service === 'abb' || service === 'abb_exec') return 'adb shell cmd ' + (payload.includes('\0') ? payload.replace(/\0+$/, '').split('\0').map(quote).join(' ') : payload);
  if (['reboot', 'root', 'unroot', 'remount', 'disable-verity', 'enable-verity', 'tcpip', 'usb'].includes(service)) return 'adb ' + service + (payload ? ' ' + payload : '');
  return raw;
}
export const historyCommandFamily = item => String(item.action || '').startsWith('install') ? 'adb install' : item.action === 'uninstall' ? 'adb uninstall' : '';
export function historyResultLabel(item) {
  const result = item.response;
  if (!result) return '旧记录 · 当时未采集返回内容';
  if (result.state === 'completed') {
    if (Number.isInteger(result.exitCode)) return '退出码 ' + result.exitCode + (result.exitCode === 0 ? ' · 执行完成' : ' · 执行报错');
    return result.protocolStatus ? '手机返回 ' + result.protocolStatus : '已收到结束结果';
  }
  return { pending: '等待返回／正在接收', closed: '返回通道已结束 · 未提供退出码', interrupted: '连接中断 · 结果可能不完整', unavailable: '此类返回内容未采集', unparsed: '返回协议未完整解析' }[result.state] || '返回状态未知';
}
export function historyMatches(item, filter, query) {
  return (filter === 'all' || filter === historyCategory(item) || filter === 'install' && String(item.action || '').startsWith('install'))
    && [item.command, historyCommand(item), historyCommandFamily(item), item.peer, item.response?.stdout, item.response?.stderr].join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase());
}
