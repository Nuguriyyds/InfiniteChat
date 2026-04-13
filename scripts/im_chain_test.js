#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const { URL } = require('url');
const { spawn } = require('child_process');

const DEFAULTS = {
  gatewayBase: 'http://127.0.0.1:10010',
  wsUrl: 'ws://127.0.0.1:9101/api/v1/chat/message',
  tokens: '/opt/infinitechat/tokens.txt',
  sendData: '/opt/infinitechat/send_msg_data.txt',
  scenario: 'both',
  caseIndex: 0,
  timeoutMs: 10000,
  pollIntervalMs: 500,
  offlineDrainRounds: 3,
  offlineDrainPageSize: 100,
  verbose: false,
  mysqlBin: 'mysql',
  mysqlHost: '',
  mysqlPort: 3306,
  mysqlUser: '',
  mysqlPassword: '',
  mysqlDatabase: '',
  mysqlVerify: false
};

function printHelp() {
  console.log([
    'Usage:',
    '  node scripts/im_chain_test.js [options]',
    '',
    'Options:',
    '  --gateway-base=<url>         HTTP gateway base URL',
    '  --ws-url=<url>               RTC direct WS URL',
    '  --tokens=<path>              tokens.txt path (userId|token|nettyUrl)',
    '  --send-data=<path>           send_msg_data.txt path (userId|token|sessionId|receiveUserId)',
    '  --scenario=<online|offline|both>',
    '  --case-index=<n>             Start from the nth valid single-chat case',
    '  --timeout-ms=<ms>            Timeout for each wait step',
    '  --poll-interval-ms=<ms>      Poll interval for /sync and /offline/pull checks',
    '  --offline-drain-rounds=<n>   Drain stale offline backlog before offline case',
    '  --offline-drain-page-size=<n>',
    '  --verbose=<true|false>',
    '',
    'Optional MySQL verification:',
    '  --mysql-verify=<true|false>',
    '  --mysql-bin=<path>',
    '  --mysql-host=<host>',
    '  --mysql-port=<port>',
    '  --mysql-user=<user>',
    '  --mysql-password=<password>',
    '  --mysql-database=<db>',
    '',
    'Examples:',
    '  node scripts/im_chain_test.js --scenario=online --tokens=/opt/infinitechat/tokens.txt --send-data=/opt/infinitechat/send_msg_data.txt',
    '  node scripts/im_chain_test.js --scenario=both --gateway-base=http://127.0.0.1:10010 --verbose=true',
    '',
    'What this script validates:',
    '  1. HTTP /api/message/send response',
    '  2. Online receiver WS delivery',
    '  3. Async persistence via /api/message/sync',
    '  4. Offline receiver pull via /api/offline/pull',
    '  5. Optional MySQL record lookup when mysql CLI is available',
    '',
    'Notes:',
    '  1. HTTP still goes through Gateway, while WS defaults to direct RTC under the formal contract.',
    '  2. To exercise the retained Gateway WS proxy entry instead, override --ws-url=ws://127.0.0.1:10010/api/v1/chat/message'
  ].join('\n'));
}

function toCamelCase(key) {
  return key.replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
}

function parseBoolean(value, key) {
  if (value === 'true') {
    return true;
  }
  if (value === 'false') {
    return false;
  }
  throw new Error(`Invalid boolean for --${key}: ${value}`);
}

function parseNumber(value, key) {
  const num = Number(value);
  if (!Number.isFinite(num) || num < 0) {
    throw new Error(`Invalid number for --${key}: ${value}`);
  }
  return num;
}

function parseArgs(argv) {
  const options = { ...DEFAULTS };

  for (const arg of argv) {
    if (arg === '--help' || arg === '-h') {
      options.help = true;
      return options;
    }
    if (!arg.startsWith('--')) {
      throw new Error(`Unsupported argument: ${arg}`);
    }

    const body = arg.slice(2);
    const eqIndex = body.indexOf('=');
    const rawKey = eqIndex === -1 ? body : body.slice(0, eqIndex);
    const rawValue = eqIndex === -1 ? 'true' : body.slice(eqIndex + 1);
    const key = toCamelCase(rawKey);

    switch (key) {
      case 'gatewayBase':
      case 'wsUrl':
      case 'tokens':
      case 'sendData':
      case 'scenario':
      case 'mysqlBin':
      case 'mysqlHost':
      case 'mysqlUser':
      case 'mysqlPassword':
      case 'mysqlDatabase':
        options[key] = rawValue;
        break;
      case 'caseIndex':
      case 'timeoutMs':
      case 'pollIntervalMs':
      case 'offlineDrainRounds':
      case 'offlineDrainPageSize':
      case 'mysqlPort':
        options[key] = parseNumber(rawValue, rawKey);
        break;
      case 'verbose':
      case 'mysqlVerify':
        options[key] = parseBoolean(rawValue, rawKey);
        break;
      default:
        throw new Error(`Unknown option: --${rawKey}`);
    }
  }

  if (!['online', 'offline', 'both'].includes(options.scenario)) {
    throw new Error(`Unsupported scenario: ${options.scenario}`);
  }

  return options;
}

function log(message) {
  console.log(`[${new Date().toISOString()}] ${message}`);
}

function verbose(options, message) {
  if (options.verbose) {
    log(message);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomSuffix() {
  return `${Date.now()}_${crypto.randomBytes(4).toString('hex')}`;
}

function loadTokens(tokenFile) {
  const fullPath = path.resolve(tokenFile);
  const raw = fs.readFileSync(fullPath, 'utf8');
  const tokenMap = new Map();

  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }
    const parts = trimmed.split('|');
    if (parts.length < 2) {
      continue;
    }
    const userId = parts[0].trim();
    const token = parts[1].trim();
    const wsUrl = parts[2] ? parts[2].trim() : '';
    if (!userId || !token) {
      continue;
    }
    tokenMap.set(userId, { token, wsUrl });
  }

  return tokenMap;
}

function loadSendEntries(sendDataFile) {
  const fullPath = path.resolve(sendDataFile);
  const raw = fs.readFileSync(fullPath, 'utf8');
  const entries = [];

  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }
    const parts = trimmed.split('|');
    if (parts.length < 4) {
      continue;
    }
    entries.push({
      senderId: parts[0].trim(),
      senderToken: parts[1].trim(),
      sessionId: parts[2].trim(),
      receiverId: parts[3].trim()
    });
  }

  return entries;
}

function filterValidCases(entries, tokenMap) {
  return entries.filter((entry) =>
    entry.senderId &&
    entry.senderToken &&
    entry.sessionId &&
    entry.receiverId &&
    entry.senderId !== entry.receiverId &&
    tokenMap.has(entry.receiverId)
  );
}

function pickScenarioCases(validCases, startIndex) {
  if (validCases.length === 0) {
    throw new Error('No valid single-chat cases found. Make sure tokens.txt and send_msg_data.txt were generated from the same batch.');
  }

  const onlineCase = validCases[startIndex];
  if (!onlineCase) {
    throw new Error(`case-index=${startIndex} is out of range. Valid case count: ${validCases.length}`);
  }

  const offlineCase = validCases[startIndex + 1] || validCases[startIndex];
  return { onlineCase, offlineCase };
}

function buildWsUrl(baseUrl, token) {
  const url = new URL(baseUrl);
  url.searchParams.set('token', token);
  return url.toString();
}

function httpRequestJson(method, urlString, headers, body) {
  const url = new URL(urlString);
  const transport = url.protocol === 'https:' ? https : http;
  const payload = body == null ? null : JSON.stringify(body);

  const requestHeaders = { ...(headers || {}) };
  if (payload != null) {
    requestHeaders['Content-Type'] = requestHeaders['Content-Type'] || 'application/json';
    requestHeaders['Content-Length'] = Buffer.byteLength(payload);
  }

  const requestOptions = {
    method,
    hostname: url.hostname,
    port: url.port || (url.protocol === 'https:' ? 443 : 80),
    path: `${url.pathname}${url.search}`,
    headers: requestHeaders
  };

  return new Promise((resolve, reject) => {
    const req = transport.request(requestOptions, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json = null;
        if (text) {
          try {
            json = JSON.parse(text);
          } catch (error) {
            // keep raw text for diagnostics
          }
        }
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          text,
          json
        });
      });
    });

    req.on('error', reject);

    if (payload != null) {
      req.write(payload);
    }
    req.end();
  });
}

function ensureHttpOk(result, stepName) {
  if (result.statusCode < 200 || result.statusCode >= 300) {
    throw new Error(`${stepName} failed with HTTP ${result.statusCode}: ${result.text}`);
  }
  return result.json;
}

function createWsMonitor(userId, token, wsUrl, options) {
  const fullUrl = buildWsUrl(wsUrl, token);
  const waiters = [];
  let ws = null;
  let open = false;
  let closed = false;

  function cleanWaiters(error) {
    while (waiters.length > 0) {
      const waiter = waiters.shift();
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }

  function onMessage(raw) {
    let parsed = null;
    try {
      parsed = JSON.parse(raw.toString());
    } catch (error) {
      verbose(options, `WS user=${userId} received non-JSON frame: ${raw.toString()}`);
      return;
    }

    if (parsed && parsed.data === 'SERVER_PING') {
      try {
        ws.send(JSON.stringify({ type: 5, data: 'CLIENT_PONG' }));
      } catch (error) {
        verbose(options, `WS user=${userId} failed to reply ping: ${error.message}`);
      }
      return;
    }

    for (let index = 0; index < waiters.length; index += 1) {
      const waiter = waiters[index];
      let matched = false;
      try {
        matched = waiter.matcher(parsed);
      } catch (error) {
        waiters.splice(index, 1);
        clearTimeout(waiter.timer);
        waiter.reject(error);
        index -= 1;
        continue;
      }
      if (matched) {
        waiters.splice(index, 1);
        clearTimeout(waiter.timer);
        waiter.resolve(parsed);
        index -= 1;
      }
    }
  }

  function waitForMatch(matcher, timeoutMs) {
    return new Promise((resolve, reject) => {
      if (closed) {
        reject(new Error(`WS user=${userId} is already closed`));
        return;
      }

      const timer = setTimeout(() => {
        const idx = waiters.findIndex((item) => item.timer === timer);
        if (idx >= 0) {
          waiters.splice(idx, 1);
        }
        reject(new Error(`Timed out waiting for WS message for user=${userId}`));
      }, timeoutMs);

      waiters.push({ matcher, resolve, reject, timer });
    });
  }

  function close() {
    closed = true;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      ws.close(1000, 'im_chain_test done');
    }
    cleanWaiters(new Error(`WS user=${userId} closed`));
  }

  return new Promise((resolve, reject) => {
    ws = new WebSocket(fullUrl);
    const connectTimer = setTimeout(() => {
      if (!open) {
        closed = true;
        try {
          ws.terminate();
        } catch (error) {
          // ignore
        }
        reject(new Error(`Timed out opening WS for user=${userId}`));
      }
    }, options.timeoutMs);

    ws.on('open', () => {
      open = true;
      clearTimeout(connectTimer);
      verbose(options, `WS connected user=${userId}`);
      resolve({ waitForMatch, close });
    });

    ws.on('message', onMessage);

    ws.on('unexpected-response', (_request, response) => {
      clearTimeout(connectTimer);
      closed = true;
      response.resume();
      reject(new Error(`WS unexpected response for user=${userId}: ${response.statusCode}`));
    });

    ws.on('error', (error) => {
      if (!open && !closed) {
        clearTimeout(connectTimer);
        closed = true;
        reject(error);
        return;
      }
      verbose(options, `WS error user=${userId}: ${error.message}`);
    });

    ws.on('close', (code, reason) => {
      clearTimeout(connectTimer);
      if (!closed) {
        closed = true;
        verbose(options, `WS closed user=${userId} code=${code} reason=${reason.toString()}`);
        cleanWaiters(new Error(`WS user=${userId} closed code=${code}`));
      }
    });
  });
}

function buildSendBody(entry, tag) {
  const clientMsgId = `chain_${tag}_${entry.senderId}_${randomSuffix()}`;
  const content = `im-chain-test ${tag} ${randomSuffix()}`;

  return {
    clientMsgId,
    sessionId: entry.sessionId,
    sessionType: 1,
    type: 1,
    receiveUserId: Number(entry.receiverId),
    body: {
      content
    }
  };
}

async function sendMessage(entry, payload, gatewayBase, options) {
  verbose(options, `HTTP send sender=${entry.senderId} receiver=${entry.receiverId} session=${entry.sessionId} clientMsgId=${payload.clientMsgId}`);
  const response = await httpRequestJson(
    'POST',
    `${gatewayBase}/api/message/send`,
    {
      Authorization: `Bearer ${entry.senderToken}`,
      'X-User-Id': entry.senderId
    },
    payload
  );

  const json = ensureHttpOk(response, 'send message');
  if (!json || !json.messageId || typeof json.seq !== 'number') {
    throw new Error(`Unexpected /api/message/send response: ${response.text}`);
  }
  return json;
}

async function pollSync(entry, expected, gatewayBase, options) {
  const beginSeq = Math.max((expected.seq || 1) - 1, 0);
  const endSeq = expected.seq || beginSeq + 1;
  const deadline = Date.now() + options.timeoutMs;
  let lastText = '';

  while (Date.now() < deadline) {
    const response = await httpRequestJson(
      'GET',
      `${gatewayBase}/api/message/sync?sessionId=${encodeURIComponent(entry.sessionId)}&beginSeq=${beginSeq}&endSeq=${endSeq}`,
      {
        Authorization: `Bearer ${entry.senderToken}`,
        'X-User-Id': entry.senderId
      }
    );

    lastText = response.text;
    const json = ensureHttpOk(response, 'sync message');
    const messages = Array.isArray(json) ? json : [];
    const matched = messages.find((item) =>
      item &&
      (item.messageId === expected.messageId || item.clientMsgId === expected.clientMsgId || item.seq === expected.seq)
    );
    if (matched) {
      return matched;
    }
    await sleep(options.pollIntervalMs);
  }

  throw new Error(`Message was not visible via /api/message/sync in time. Last response: ${lastText}`);
}

async function drainOfflineBacklog(receiverId, receiverToken, sessionId, gatewayBase, options) {
  for (let round = 0; round < options.offlineDrainRounds; round += 1) {
    const response = await httpRequestJson(
      'POST',
      `${gatewayBase}/api/offline/pull`,
      {
        Authorization: `Bearer ${receiverToken}`,
        'X-User-Id': receiverId
      },
      {
        sessionId,
        lastSeq: 0,
        pageSize: options.offlineDrainPageSize
      }
    );

    const json = ensureHttpOk(response, 'drain offline backlog');
    const messages = (json && json.data && Array.isArray(json.data.messages)) ? json.data.messages : [];
    if (messages.length === 0) {
      return;
    }
    verbose(options, `Drained ${messages.length} old offline messages for receiver=${receiverId}, session=${sessionId}`);
    await sleep(100);
  }
}

async function pollOfflinePull(receiverId, receiverToken, sessionId, expected, gatewayBase, options) {
  const deadline = Date.now() + options.timeoutMs;
  let lastText = '';

  while (Date.now() < deadline) {
    const response = await httpRequestJson(
      'POST',
      `${gatewayBase}/api/offline/pull`,
      {
        Authorization: `Bearer ${receiverToken}`,
        'X-User-Id': receiverId
      },
      {
        sessionId,
        lastSeq: Math.max((expected.seq || 1) - 1, 0),
        pageSize: options.offlineDrainPageSize
      }
    );

    lastText = response.text;
    const json = ensureHttpOk(response, 'offline pull');
    const messages = (json && json.data && Array.isArray(json.data.messages)) ? json.data.messages : [];
    const matched = messages.find((item) =>
      item &&
      (item.messageId === expected.messageId || item.clientMsgId === expected.clientMsgId)
    );
    if (matched) {
      return matched;
    }

    await sleep(options.pollIntervalMs);
  }

  throw new Error(`Message was not visible via /api/offline/pull in time. Last response: ${lastText}`);
}

function escapeSqlString(value) {
  return String(value).replace(/\\/g, '\\\\').replace(/'/g, "''");
}

function runMysqlQuery(sql, options) {
  return new Promise((resolve, reject) => {
    const args = [
      `-h${options.mysqlHost}`,
      `-P${options.mysqlPort}`,
      `-u${options.mysqlUser}`,
      `-p${options.mysqlPassword}`,
      '--batch',
      '--skip-column-names',
      options.mysqlDatabase,
      '-e',
      sql
    ];

    const child = spawn(options.mysqlBin, args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString('utf8');
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString('utf8');
    });

    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) {
        reject(new Error(`mysql exited with code ${code}: ${stderr || stdout}`));
        return;
      }
      resolve(stdout.trim());
    });
  });
}

function shouldMysqlVerify(options) {
  return Boolean(
    options.mysqlVerify &&
    options.mysqlHost &&
    options.mysqlUser &&
    options.mysqlPassword &&
    options.mysqlDatabase
  );
}

async function verifyMysqlMessage(expected, options) {
  const sql = [
    'SELECT message_id, client_msg_id, seq, session_id',
    'FROM im_message',
    `WHERE message_id = '${escapeSqlString(expected.messageId)}'`,
    'LIMIT 1;'
  ].join(' ');

  const output = await runMysqlQuery(sql, options);
  if (!output) {
    throw new Error(`MySQL im_message lookup missed messageId=${expected.messageId}`);
  }
  return output;
}

async function verifyMysqlOffline(expected, receiverId, options) {
  const sql = [
    'SELECT message_id, receiver_id, session_id, seq',
    'FROM offline_message',
    `WHERE message_id = '${escapeSqlString(expected.messageId)}'`,
    `AND receiver_id = ${Number(receiverId)}`,
    'LIMIT 1;'
  ].join(' ');

  const output = await runMysqlQuery(sql, options);
  if (!output) {
    throw new Error(`MySQL offline_message lookup missed messageId=${expected.messageId}, receiverId=${receiverId}`);
  }
  return output;
}

async function runOnlineScenario(entry, receiverToken, gatewayBase, wsUrl, options) {
  const result = {
    name: 'online',
    senderId: entry.senderId,
    receiverId: entry.receiverId,
    sessionId: entry.sessionId,
    checks: []
  };

  const payload = buildSendBody(entry, 'online');
  const monitor = await createWsMonitor(entry.receiverId, receiverToken, wsUrl, options);

  try {
    const waitForPush = monitor.waitForMatch(
      (message) => message && message.clientMsgId === payload.clientMsgId,
      options.timeoutMs
    );

    const sendResponse = await sendMessage(entry, payload, gatewayBase, options);
    result.http = sendResponse;
    result.checks.push('http_send');

    const pushedMessage = await waitForPush;
    result.ws = pushedMessage;
    result.checks.push('ws_delivery');

    const syncedMessage = await pollSync(
      entry,
      {
        clientMsgId: payload.clientMsgId,
        messageId: sendResponse.messageId,
        seq: sendResponse.seq
      },
      gatewayBase,
      options
    );
    result.sync = syncedMessage;
    result.checks.push('sync_visible');

    if (shouldMysqlVerify(options)) {
      result.mysql = await verifyMysqlMessage(
        {
          messageId: sendResponse.messageId
        },
        options
      );
      result.checks.push('mysql_im_message');
    }

    return result;
  } finally {
    monitor.close();
    await sleep(300);
  }
}

async function runOfflineScenario(entry, receiverToken, gatewayBase, options) {
  const result = {
    name: 'offline',
    senderId: entry.senderId,
    receiverId: entry.receiverId,
    sessionId: entry.sessionId,
    checks: []
  };

  await drainOfflineBacklog(entry.receiverId, receiverToken, entry.sessionId, gatewayBase, options);

  const payload = buildSendBody(entry, 'offline');
  const sendResponse = await sendMessage(entry, payload, gatewayBase, options);
  result.http = sendResponse;
  result.checks.push('http_send');

  const syncedMessage = await pollSync(
    entry,
    {
      clientMsgId: payload.clientMsgId,
      messageId: sendResponse.messageId,
      seq: sendResponse.seq
    },
    gatewayBase,
    options
  );
  result.sync = syncedMessage;
  result.checks.push('sync_visible');

  const pulledMessage = await pollOfflinePull(
    entry.receiverId,
    receiverToken,
    entry.sessionId,
    {
      clientMsgId: payload.clientMsgId,
      messageId: sendResponse.messageId,
      seq: sendResponse.seq
    },
    gatewayBase,
    options
  );
  result.pull = pulledMessage;
  result.checks.push('offline_pull');

  if (shouldMysqlVerify(options)) {
    result.mysqlMessage = await verifyMysqlMessage(
      {
        messageId: sendResponse.messageId
      },
      options
    );
    result.checks.push('mysql_im_message');

    result.mysqlOffline = await verifyMysqlOffline(
      {
        messageId: sendResponse.messageId
      },
      entry.receiverId,
      options
    );
    result.checks.push('mysql_offline_message');
  }

  return result;
}

function summarizeResult(result) {
  const summary = {
    name: result.name,
    senderId: result.senderId,
    receiverId: result.receiverId,
    sessionId: result.sessionId,
    messageId: result.http && result.http.messageId,
    clientMsgId: result.http && result.http.clientMsgId,
    seq: result.http && result.http.seq,
    checks: result.checks
  };

  if (result.ws) {
    summary.wsMessageId = result.ws.messageId;
    summary.wsSeq = result.ws.seq;
  }
  if (result.pull) {
    summary.pullMessageId = result.pull.messageId;
    summary.pullSeq = result.pull.seq;
  }
  return summary;
}

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    printHelp();
    process.exit(1);
    return;
  }

  if (options.help) {
    printHelp();
    return;
  }

  let WebSocket;
  try {
    WebSocket = require('ws');
  } catch (error) {
    console.error('Missing dependency "ws". Install it with: npm install ws');
    process.exit(1);
    return;
  }

  const tokenMap = loadTokens(options.tokens);
  const sendEntries = loadSendEntries(options.sendData);
  const validCases = filterValidCases(sendEntries, tokenMap);
  const { onlineCase, offlineCase } = pickScenarioCases(validCases, options.caseIndex);

  log(`Loaded tokens=${tokenMap.size}, validCases=${validCases.length}, scenario=${options.scenario}`);

  const reports = [];
  const startedAt = Date.now();

  try {
    if (options.scenario === 'online' || options.scenario === 'both') {
      const receiverInfo = tokenMap.get(onlineCase.receiverId);
      const onlineReport = await runOnlineScenario(
        onlineCase,
        receiverInfo.token,
        options.gatewayBase,
        receiverInfo.wsUrl || options.wsUrl,
        options
      );
      reports.push(summarizeResult(onlineReport));
      log(`ONLINE PASS messageId=${onlineReport.http.messageId} seq=${onlineReport.http.seq}`);
    }

    if (options.scenario === 'offline' || options.scenario === 'both') {
      const receiverInfo = tokenMap.get(offlineCase.receiverId);
      const offlineReport = await runOfflineScenario(
        offlineCase,
        receiverInfo.token,
        options.gatewayBase,
        options
      );
      reports.push(summarizeResult(offlineReport));
      log(`OFFLINE PASS messageId=${offlineReport.http.messageId} seq=${offlineReport.http.seq}`);
    }

    log(`All requested scenarios passed in ${Date.now() - startedAt} ms`);
    console.log(JSON.stringify({ ok: true, reports }, null, 2));
  } catch (error) {
    console.error(error.stack || error.message);
    console.log(JSON.stringify({ ok: false, reports }, null, 2));
    process.exit(1);
  }
}

main();
