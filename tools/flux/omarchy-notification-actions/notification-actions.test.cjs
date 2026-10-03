const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const sourceDir = process.env.OHM_FLUX_NOTIFICATION_PLUGIN;
if (!sourceDir) throw new Error('Set OHM_FLUX_NOTIFICATION_PLUGIN to the patched Omarchy notification plugin directory.');
const logic = require(path.join(sourceDir, 'NotificationLogic.js'));
const qml = fs.readFileSync(path.join(sourceDir, 'Service.qml'), 'utf8');

function functionSource(name) {
  const start = qml.indexOf(`function ${name}(`);
  assert.notEqual(start, -1);
  const open = qml.indexOf('{', start);
  let depth = 1, quote = null, lineComment = false, blockComment = false;
  for (let i = open + 1; i < qml.length; i++) {
    const c = qml[i], n = qml[i + 1];
    if (lineComment) { if (c === '\n') lineComment = false; continue; }
    if (blockComment) { if (c === '*' && n === '/') { blockComment = false; i++; } continue; }
    if (quote) { if (c === '\\') i++; else if (c === quote) quote = null; continue; }
    if (c === '/' && n === '/') { lineComment = true; i++; continue; }
    if (c === '/' && n === '*') { blockComment = true; i++; continue; }
    if (c === '"' || c === "'" || c === '`') { quote = c; continue; }
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) return qml.slice(start, i + 1);
  }
  throw new Error(`Unclosed function ${name}`);
}

function fixture() {
  const rows = [];
  const archived = [];
  const context = {
    NotificationLogic: logic, console,
    actionRefs: {}, liveRefs: {}, restoredPopups: {}, nextActionToken: 0,
    popupModel: {
      get count() { return rows.length; },
      get(index) { return rows[index]; },
      remove(index) { rows.splice(index, 1); },
      setProperty(index, key, value) { rows[index][key] = value; },
    },
    archivePopupFileFor(row) { archived.push(row.actionToken); },
    persistPopupFile() {},
  };
  context.service = context;
  vm.createContext(context);
  for (const name of ['trackActionOwner', 'isRestoredRow', 'removePopup', 'invokePopupAction', 'refreshPopup'])
    vm.runInContext(functionSource(name), context);
  function add(ref, id = 9) {
    const row = {originalId: id, id, timestamp: Date.now(),
      actionToken: context.trackActionOwner(ref), actionsJson: logic.explicitActionsJson(ref.actions)};
    rows.push(row);
    context.liveRefs[id] = ref;
    return row;
  }
  return {context, rows, add, archived};
}

function notification(actions) {
  return {actions, tracked: true, dismissCount: 0, dismiss() { this.dismissCount++; }};
}

test('scalar snapshots expose both explicit labels and omit the default action', () => {
  const actions = [
    {identifier: 'default', text: 'Open', invoke() {}},
    {identifier: 'input-approve:one', text: 'Approve', invoke() {}},
    {identifier: 'input-deny:one', text: 'Deny', invoke() {}},
  ];
  const row = logic.snapshotOf({id: 9, actions}, 10);
  assert.deepEqual(JSON.parse(row.actionsJson), [
    {identifier: 'input-approve:one', label: 'Approve'},
    {identifier: 'input-deny:one', label: 'Deny'},
  ]);
  assert.equal(typeof row.actionsJson, 'string');
  assert.equal(row.actionsJson.includes('invoke'), false);
});

test('persisted and replayed rows discard forged or old live actions and tokens', () => {
  const row = {id: 9, originalId: 9, timestamp: 10, actionsJson: '[{"identifier":"approve"}]', actionToken: '1'};
  const serialized = logic.serializePopup(row, 1);
  const restored = logic.parsePopupFiles(serialized, 1)[0];
  const history = logic.historyRows(serialized, [row], 1, 10)[0];
  for (const candidate of [restored, history]) {
    assert.equal(candidate.actionsJson, '[]');
    assert.equal(candidate.actionToken, '');
  }
});

test('an explicit click invokes only its matching action and removes its own row', () => {
  const f = fixture();
  let approve = 0, deny = 0;
  const ref = notification([
    {identifier: 'approve', invoke() { approve++; }},
    {identifier: 'deny', invoke() { deny++; }},
  ]);
  const row = f.add(ref);
  f.context.invokePopupAction(row.actionToken, 'approve');
  assert.equal(approve, 1);
  assert.equal(deny, 0);
  assert.equal(ref.dismissCount, 1);
  assert.equal(f.rows.length, 0);
});

test('default, unknown, closed-owner, and restored clicks are inert', () => {
  const f = fixture();
  let invoked = 0;
  const ref = notification([{identifier: 'approve', invoke() { invoked++; }}]);
  const row = f.add(ref);
  f.context.invokePopupAction(row.actionToken, 'default');
  f.context.invokePopupAction(row.actionToken, 'unknown');
  f.context.invokePopupAction('absent', 'approve');
  f.context.restoredPopups[logic.popupFileName(row)] = true;
  f.context.invokePopupAction(row.actionToken, 'approve');
  delete f.context.restoredPopups[logic.popupFileName(row)];
  delete f.context.actionRefs[row.actionToken];
  f.context.invokePopupAction(row.actionToken, 'approve');
  assert.equal(invoked, 0);
  assert.equal(ref.dismissCount, 0);
  assert.equal(f.rows.length, 1);
});

test('a stale token never invokes a new notification reusing its server id', () => {
  const f = fixture();
  let oldInvoked = 0, newInvoked = 0;
  const old = notification([{identifier: 'approve', invoke() { oldInvoked++; }}]);
  const current = notification([{identifier: 'approve', invoke() { newInvoked++; }}]);
  const oldRow = f.add(old, 9);
  f.add(current, 9);
  f.context.invokePopupAction(oldRow.actionToken, 'approve');
  assert.equal(oldInvoked, 0);
  assert.equal(newInvoked, 0);
  assert.equal(current.dismissCount, 0);
  assert.equal(f.rows.length, 2);
});

test('synchronous id reuse and row insertion cannot dismiss the new owner', () => {
  const f = fixture();
  const replacement = notification([]);
  const old = notification([{identifier: 'approve', invoke() {
    const fresh = f.add(replacement, 9);
    f.rows.pop();
    f.rows.unshift(fresh);
  }}]);
  const oldRow = f.add(old, 9);
  f.context.invokePopupAction(oldRow.actionToken, 'approve');
  assert.equal(replacement.dismissCount, 0);
  assert.equal(old.dismissCount, 0);
  assert.equal(f.rows.length, 1);
  assert.notEqual(f.rows[0].actionToken, oldRow.actionToken);
  assert.deepEqual(f.archived, [oldRow.actionToken]);
});

test('a synchronous sender close still removes the exact old card', () => {
  const f = fixture();
  let row;
  const ref = notification([{identifier: 'approve', invoke() {
    delete f.context.actionRefs[row.actionToken];
    delete f.context.liveRefs[9];
    row.actionsJson = '[]';
  }}]);
  row = f.add(ref, 9);
  f.context.invokePopupAction(row.actionToken, 'approve');
  assert.equal(f.rows.length, 0);
  assert.equal(ref.dismissCount, 0);
});

test('action updates replace displayed labels without changing the owner token', () => {
  const f = fixture();
  const ref = notification([{identifier: 'approve', text: 'Approve'}]);
  const row = f.add(ref, 9);
  const token = row.actionToken;
  ref.actions = [{identifier: 'deny', text: 'Deny'}];
  f.context.refreshPopup(ref, 9, row.timestamp);
  assert.equal(row.actionToken, token);
  assert.deepEqual(JSON.parse(row.actionsJson), [{identifier: 'deny', label: 'Deny'}]);
});

test('owner tokens fail closed before double precision can reuse an identifier', () => {
  const f = fixture();
  f.context.nextActionToken = 9007199254740990;
  assert.equal(f.context.trackActionOwner({}), '9007199254740991');
  assert.equal(f.context.trackActionOwner({}), '');
  assert.equal(Object.keys(f.context.actionRefs).length, 1);
});

test('a close before deferred insertion cannot show stale action buttons', () => {
  const f = fixture();
  const later = [], closed = [];
  Object.assign(f.context, {
    Qt: {callLater(callback) { later.push(callback); }},
    doNotDisturb: false,
    snapshotOf(ref) { return logic.snapshotOf(ref, 10); },
    watchForUpdates() {}, removePopupsByOriginalId() {},
  });
  f.context.popupModel.insert = (index, row) => f.rows.splice(index, 0, row);
  vm.runInContext(functionSource('handleNotification'), f.context);
  const ref = notification([{identifier: 'approve', text: 'Approve'}]);
  ref.id = 9;
  ref.closed = {connect(callback) { closed.push(callback); }};
  f.context.handleNotification(ref);
  for (const callback of closed) callback();
  for (const callback of later) callback();
  assert.equal(f.rows.length, 1);
  assert.equal(f.rows[0].actionsJson, '[]');
  assert.equal(Object.keys(f.context.actionRefs).length, 0);
});
