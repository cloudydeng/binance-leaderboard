const form = document.getElementById('run-form');
const runButton = document.getElementById('run-button');
let csrfToken = '';
let pollTimer = null;

const byId = id => document.getElementById(id);
const show = (element, visible) => { element.hidden = !visible; };
const value = text => text == null || text === '' ? '—' : String(text);

function setStatus(state) {
  const badge = byId('status-badge');
  badge.textContent = ({ IDLE: '等待开始', RUNNING: '抓取中', COMPLETE: '完整', INCOMPLETE: '未完成', ERROR: '失败' })[state.status] || state.status;
  badge.className = 'status-badge ' + state.status.toLowerCase();
  byId('status-message').textContent = value(state.message);
  byId('pages-value').textContent = value(state.pagesRequested);
  byId('records-label').textContent = state.result ? '排名范围人数' : '已读取记录';
  byId('records-value').textContent = value(state.recordsRead);
  show(byId('spinner'), state.status === 'RUNNING');
  runButton.disabled = !csrfToken || state.status === 'RUNNING';

  const issueList = byId('issue-list');
  issueList.replaceChildren();
  const issues = state.result?.issues || [];
  for (const issue of issues) {
    const item = document.createElement('li');
    item.textContent = issue;
    issueList.append(item);
  }
  show(issueList, issues.length > 0);
  show(byId('result-panel'), !!state.result);
  if (state.result) renderResult(state.result);

  if (state.status === 'RUNNING') {
    if (!pollTimer) pollTimer = setInterval(loadStatus, 1200);
  } else if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function renderResult(result) {
  const complete = result.completeness === 'COMPLETE';
  const resultState = byId('result-state');
  resultState.textContent = complete ? 'COMPLETE' : 'INCOMPLETE · 仅供排障';
  resultState.className = 'status-badge ' + result.completeness.toLowerCase();
  byId('result-resource').textContent = value(result.resourceId);
  byId('result-range').textContent = result.startRank + ' – ' + value(result.maxRank);
  byId('result-total').textContent = value(result.totalVolume);
  byId('result-count').textContent = value(result.rankFilteredCount);
  byId('result-average').textContent = value(result.averageVolume);
  byId('result-rank1000').textContent = value(result.rank1000Volume);
  byId('result-server-count').textContent = value(result.serverEligibleUserCount);

  const reward = result.rewardEstimate;
  show(byId('reward-box'), !!reward);
  if (!reward) return;
  byId('reward-unit').textContent = reward.rewardUnit || '未指定币种';
  byId('reward-1000').textContent = value(reward.rewardPer1000);
  byId('reward-10000').textContent = value(reward.rewardPer10000);
  byId('reward-cap-volume').textContent = value(reward.capVolume);
  const list = byId('account-list');
  list.replaceChildren();
  for (let i = 0; i < reward.accounts.length; i++) {
    const row = document.createElement('div');
    row.className = 'account-row';
    const account = reward.accounts[i];
    const label = document.createElement('span');
    label.textContent = '账户 ' + (i + 1) + ' · 交易量 ' + account.volume;
    const amount = document.createElement('strong');
    amount.textContent = account.capped + (reward.rewardUnit ? ' ' + reward.rewardUnit : '');
    row.append(label, amount);
    list.append(row);
  }
  show(byId('accounts-box'), reward.accounts.length > 0);
}

async function loadStatus() {
  try {
    const response = await fetch('/api/status', { cache: 'no-store' });
    if (!response.ok) throw new Error('无法读取运行状态');
    setStatus(await response.json());
  } catch (error) {
    byId('status-message').textContent = error.message;
  }
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (!form.reportValidity()) return;
  const data = {};
  for (const [key, raw] of new FormData(form)) {
    const text = String(raw).trim();
    if (text) data[key] = text;
  }
  runButton.disabled = true;
  byId('status-message').textContent = '正在提交配置…';
  try {
    const response = await fetch('/api/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
      body: JSON.stringify(data)
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || '提交失败');
    await loadStatus();
  } catch (error) {
    byId('status-message').textContent = error.message;
    runButton.disabled = false;
  }
});

(async () => {
  try {
    const response = await fetch('/api/config', { cache: 'no-store' });
    if (!response.ok) throw new Error('无法连接本地服务');
    csrfToken = (await response.json()).csrfToken;
    await loadStatus();
  } catch (error) {
    byId('status-message').textContent = error.message;
  }
})();
