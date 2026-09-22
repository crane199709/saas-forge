/* global document, window */
import assert from 'node:assert/strict';
import test from 'node:test';
import { createConsoleTestServer } from './console-test-server.mjs';
import { chromium, firefox, webkit } from 'playwright';

// 聚焦默认应用入口的浏览器能力接线；模拟 HTTP 仅用于快速回归，不替代真实 TLS/IAM 验收。
for (const [application, directory, heading] of [
  ['Platform', 'platform-console', 'Platform 总览'],
  ['Tenant', 'tenant-console-shell', 'Tenant 工作台'],
]) {
  test(`default ${application} Console entry coordinates a single refresh across native tabs`, async (t) => {
    const server = await createConsoleTestServer(directory);
    t.after(() => server.close());
    await server.listen();
    const browser = await { chromium, firefox, webkit }[
      process.env.SF_BROWSER ?? 'chromium'
    ].launch({
      channel: process.env.SF_BROWSER_CHANNEL || undefined,
    });
    t.after(() => browser.close());
    const context = await browser.newContext();
    await context.addInitScript(() => {
      Object.defineProperty(navigator, 'languages', { configurable: true, value: ['zh-CN'] });
    });
    await context.addInitScript(() => {
      // 观察实际 Refresh；无协调能力的旧入口也能到达屏障并暴露重复请求。
      window.sessionOperationStarted = false;
      const nativeFetch = window.fetch.bind(window);
      window.fetch = (...args) => {
        if (String(args[0]).endsWith('/api/v1/auth/refresh')) window.sessionOperationStarted = true;
        return nativeFetch(...args);
      };
    });
    let release;
    const ready = new Promise((resolve) => {
      release = resolve;
    });
    let refreshes = 0;
    const tenantContext = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6077',
      tenantDisplayName: 'Coordination Tenant',
      accessibleMemberships: [
        {
          membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
          tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6077',
          tenantDisplayName: 'Coordination Tenant',
        },
      ],
    };
    await context.route('https://api.saas.forge.test/api/v1/auth/**', async (route) => {
      if (new URL(route.request().url()).pathname === '/api/v1/auth/refresh') {
        assert.equal(route.request().postDataJSON().sessionSlot, application.toUpperCase());
        refreshes += 1;
        await ready;
        await route.fulfill({
          json: {
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'focused-regression-token',
            tokenType: 'Bearer',
            expiresIn: 120,
            ...(application === 'Tenant' ? { tenantContext } : {}),
          },
        });
      } else if (new URL(route.request().url()).pathname === '/api/v1/auth/session') {
        await route.fulfill({
          json: {
            identityId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
            email: 'admin@example.test',
            platformAdmin: true,
          },
        });
      } else {
        assert.equal(new URL(route.request().url()).pathname, '/api/v1/auth/context');
        await route.fulfill({ json: tenantContext });
      }
    });
    const pages = await Promise.all([context.newPage(), context.newPage()]);
    const address = server.httpServer.address();
    await Promise.all(pages.map((page) => page.goto(`http://127.0.0.1:${address.port}/`)));
    // 原生队列证明另一页已经开始恢复；不要覆写 LockManager 实例方法，
    // WebKit 下该观察器可能漏报，而公开 query 已显示持锁者和等待者。
    const lockName = `sf:session:https://api.saas.forge.test:${application.toUpperCase()}`;
    await Promise.all(pages.map((page) => waitForSessionOperation(page, lockName)));
    release();
    for (const page of pages) {
      await page.getByRole('heading', { name: heading, exact: true }).waitFor();
      const localeBounds = await localeControl(page, application).trigger.boundingBox();
      const logoutBounds = await page
        .getByRole('button', { name: '退出登录', exact: true })
        .boundingBox();
      assert.ok(localeBounds && logoutBounds);
      assert.ok(
        localeBounds.y + localeBounds.height <= logoutBounds.y ||
          logoutBounds.y + logoutBounds.height <= localeBounds.y ||
          localeBounds.x + localeBounds.width <= logoutBounds.x ||
          logoutBounds.x + logoutBounds.width <= localeBounds.x,
        'Locale control must leave the authenticated logout action unobstructed',
      );
    }
    assert.equal(refreshes, 1, 'one coordinated browser session must perform one refresh');

    const [source, peer] = pages;
    await selectConsoleLocale(source, application, 'English');
    await peer.waitForFunction(() => document.documentElement.lang === 'en-US');
    assert.equal(await source.evaluate(() => localStorage.getItem('sf:ui:locale')), 'en-US');
    assert.equal(await peer.evaluate(() => localStorage.getItem('sf:ui:locale')), 'en-US');
    assert.equal(refreshes, 1, 'switching the display language must not trigger session recovery');
  });
}

test('Platform 与 Tenant Console 的 Locale 偏好按 Origin 隔离', async (t) => {
  const [platformServer, tenantServer] = await Promise.all(
    ['platform-console', 'tenant-console-shell'].map(async (directory) => {
      const server = await createConsoleTestServer(directory);
      await server.listen();
      return server;
    }),
  );
  t.after(() => platformServer.close());
  t.after(() => tenantServer.close());
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL || undefined,
  });
  t.after(() => browser.close());
  const context = await browser.newContext();
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'languages', { configurable: true, value: ['zh-CN'] });
  });
  const businessRequests = [];
  context.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/')) businessRequests.push(request.url());
  });
  await context.route('**/runtime-config.json', (route) =>
    route.fulfill({
      json: { schemaVersion: 1, apiBaseUrl: 'REPLACE_DURING_DEPLOYMENT' },
    }),
  );

  const platform = await context.newPage();
  const tenant = await context.newPage();
  const platformAddress = platformServer.httpServer.address();
  const tenantAddress = tenantServer.httpServer.address();
  await Promise.all([
    platform.goto(`http://127.0.0.1:${platformAddress.port}/`),
    tenant.goto(`http://127.0.0.1:${tenantAddress.port}/`),
  ]);
  await Promise.all([
    localeControl(platform, 'Platform').trigger.waitFor(),
    localeControl(tenant, 'Tenant').trigger.waitFor(),
  ]);

  const tenantInitialLocale = await tenant.evaluate(() => document.documentElement.lang);
  await selectConsoleLocale(platform, 'Platform', 'English');
  await platform.waitForFunction(() => document.documentElement.lang === 'en-US');
  assert.equal(await platform.evaluate(() => localStorage.getItem('sf:ui:locale')), 'en-US');
  assert.equal(await tenant.evaluate(() => localStorage.getItem('sf:ui:locale')), null);
  assert.equal(await tenant.evaluate(() => document.documentElement.lang), tenantInitialLocale);

  await selectConsoleLocale(tenant, 'Tenant', 'English');
  await tenant.waitForFunction(() => document.documentElement.lang === 'en-US');
  await selectConsoleLocale(tenant, 'Tenant', '简体中文');
  await tenant.waitForFunction(() => document.documentElement.lang === 'zh-CN');
  assert.equal(await platform.evaluate(() => localStorage.getItem('sf:ui:locale')), 'en-US');
  assert.equal(await tenant.evaluate(() => localStorage.getItem('sf:ui:locale')), 'zh-CN');
  assert.deepEqual(businessRequests, []);
});

// 两个 Console 的语言控件形态不同，定位方式必须按 Console 区分，不能把其中一个的
// 可访问名或选项 role 当成两者共用：platform 是官方壳的图标下拉（触发器为带
// aria-label 的按钮，选项 role 为 menuitem），tenant 是 ElSelect（触发器 role 为
// combobox，选项 role 为 option）。platform 的按钮可访问名随当前语言变化（切换语言 /
// Switch language），因此用正则同时匹配两种语言。
function localeControl(page, application) {
  if (application === 'Platform') {
    const trigger = page.getByRole('button', { name: /^(切换语言|Switch language)$/ });
    return { trigger, optionRole: 'menuitem', open: () => trigger.click() };
  }
  const trigger = page.getByRole('combobox', { name: 'Language / 语言' });
  return { trigger, optionRole: 'option', open: () => trigger.press('Enter') };
}

async function selectConsoleLocale(page, application, name) {
  const { optionRole, open } = localeControl(page, application);
  await open();
  await page.getByRole(optionRole, { name, exact: true }).click();
}

// waitForFunction 的轮询条件不能返回 Promise：Promise 本身会提前满足真值判断。
// 在 Node 侧等待 evaluate 的异步结果，确保另一标签确实发出 Refresh 或进入原生锁队列。
async function waitForSessionOperation(page, lockName) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const started = await page.evaluate(async (name) => {
      if (window.sessionOperationStarted) return true;
      if (navigator.locks === undefined) return false;
      const locks = await navigator.locks.query();
      return (
        locks.held.some((lock) => lock.name === name) &&
        locks.pending.some((lock) => lock.name === name)
      );
    }, lockName);
    if (started) return;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  assert.fail('each tab must start session recovery before releasing the refresh response');
}
