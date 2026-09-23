import { expect, test } from '@playwright/test'

const TEMPLATE = '欢迎使用 TeamTalk，有任何问题请联系管理员。'

function broadcastFixture(overrides: Record<string, unknown> = {}) {
  return {
    broadcastId: 'bcast-1',
    markdown: '维护窗口通知：周六 02:00–03:00 升级服务端。',
    createdBy: 'admin',
    createdAt: 1758600000000,
    finishedAt: null as number | null,
    totalUsers: 5,
    coveredUsers: 3,
    failedUsers: 0,
    lastError: null as string | null,
    ...overrides,
  }
}

test.beforeEach(async ({ page }) => {
  // Only the loopback fixture sees this dummy token; every API request is intercepted below.
  await page.addInitScript(() => localStorage.setItem('tt-admin-token', 'browser-fixture'))
})

test('welcome template: loads, edits and saves', async ({ page }, info) => {
  let savedContent = ''
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/admin/service/welcome') {
      if (route.request().method() === 'PUT') {
        savedContent = (route.request().postDataJSON() as { content: string }).content
        await route.fulfill({ json: { template: savedContent } })
        return
      }
      await route.fulfill({ json: { template: TEMPLATE } })
      return
    }
    if (url.pathname === '/api/admin/service/broadcasts') {
      await route.fulfill({ json: { broadcasts: [] } })
      return
    }
    await route.fulfill({ status: 404, json: { error: 'Unexpected fixture request' } })
  })
  try {
    await page.goto('/admin/service')
    const input = page.getByTestId('service.welcome.input')
    await expect(input).toHaveValue(TEMPLATE)
    const save = page.getByTestId('service.welcome.save')
    // The draft starts empty even when a stored template exists: only real edits may save.
    await expect(save).toBeDisabled()
    await input.fill('新欢迎语：请先完善个人资料。')
    await expect(save).toBeEnabled()
    await save.click()
    await expect(page.getByText('欢迎语模板已保存')).toBeVisible()
    expect(savedContent).toBe('新欢迎语：请先完善个人资料。')
    await page.screenshot({ path: info.outputPath('welcome.png') })
  } finally {
    await page.unrouteAll({ behavior: 'wait' })
  }
})

test('broadcast: empty draft is blocked, send posts markdown and the ledger refreshes', async ({ page }, info) => {
  let listCalls = 0
  const posted: string[] = []
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/admin/service/welcome') {
      await route.fulfill({ json: { template: TEMPLATE } })
      return
    }
    if (url.pathname === '/api/admin/service/broadcasts' && route.request().method() === 'GET') {
      listCalls += 1
      const broadcasts = listCalls === 1 ? [] : [broadcastFixture()]
      await route.fulfill({ json: { broadcasts } })
      return
    }
    if (url.pathname === '/api/admin/service/broadcasts' && route.request().method() === 'POST') {
      posted.push((route.request().postDataJSON() as { markdown: string }).markdown)
      await route.fulfill({ json: broadcastFixture() })
      return
    }
    await route.fulfill({ status: 404, json: { error: 'Unexpected fixture request' } })
  })
  try {
    await page.goto('/admin/service')
    const send = page.getByTestId('service.broadcast.send')
    await expect(send).toBeDisabled()
    const draft = '升级通知：服务将于周六凌晨重启，期间消息自动补发。'
    await page.getByTestId('service.broadcast.input').fill(draft)
    await expect(send).toBeEnabled()
    await send.click()
    await expect(page.getByText('广播已发起，正在后台推送给全部用户')).toBeVisible()
    expect(posted).toEqual([draft])
    // The draft clears so a second accidental click cannot double-send.
    await expect(page.getByTestId('service.broadcast.input')).toHaveValue('')
    // The ledger reloads after the broadcast is accepted and shows progress.
    const row = page.getByRole('row').filter({ hasText: '维护窗口通知' })
    await expect(row).toBeVisible()
    await expect(row.getByText('3/5')).toBeVisible()
    await expect(row.getByText('进行中')).toBeVisible()
    await page.screenshot({ path: info.outputPath('broadcast.png') })
  } finally {
    await page.unrouteAll({ behavior: 'wait' })
  }
})

test('reissue: finished broadcasts re-push idempotently and running ones are rejected', async ({ page }, info) => {
  let reissueCalls = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/admin/service/welcome') {
      await route.fulfill({ json: { template: TEMPLATE } })
      return
    }
    if (url.pathname === '/api/admin/service/broadcasts' && route.request().method() === 'GET') {
      await route.fulfill({ json: { broadcasts: [broadcastFixture({
        finishedAt: 1758600600000,
        coveredUsers: 5,
      })] } })
      return
    }
    if (url.pathname === '/api/admin/service/broadcasts/bcast-1/reissue') {
      reissueCalls += 1
      if (reissueCalls === 1) {
        await route.fulfill({ json: broadcastFixture({ finishedAt: 1758600600000, coveredUsers: 5 }) })
        return
      }
      await route.fulfill({ status: 409, json: { error: 'broadcast is still running' } })
      return
    }
    await route.fulfill({ status: 404, json: { error: 'Unexpected fixture request' } })
  })
  try {
    await page.goto('/admin/service')
    const row = page.getByRole('row').filter({ hasText: '维护窗口通知' })
    await expect(row).toBeVisible()
    await expect(row.getByText('5/5')).toBeVisible()
    await expect(row.getByText('已完成')).toBeVisible()
    await page.getByTestId('service.broadcast.reissue.bcast-1').click()
    await expect(page.getByText('已重新推送（已送达用户自动跳过）')).toBeVisible()
    expect(reissueCalls).toBe(1)
    await page.getByTestId('service.broadcast.reissue.bcast-1').click()
    await expect(page.getByText('broadcast is still running')).toBeVisible()
    expect(reissueCalls).toBe(2)
    await page.screenshot({ path: info.outputPath('reissue.png') })
  } finally {
    await page.unrouteAll({ behavior: 'wait' })
  }
})

test('load failures surface error messages without blanking the page', async ({ page }) => {
  await page.route('**/api/**', async route => {
    await route.fulfill({ status: 500, json: { error: 'service registry unavailable' } })
  })
  try {
    await page.goto('/admin/service')
    await expect(page.getByText('service registry unavailable').first()).toBeVisible()
    // Both cards stay rendered so the operator can retry instead of reloading blind.
    await expect(page.getByText('欢迎语模板', { exact: true })).toBeVisible()
    await expect(page.getByText('全员广播', { exact: true })).toBeVisible()
    await expect(page.getByText('广播台账', { exact: true })).toBeVisible()
  } finally {
    await page.unrouteAll({ behavior: 'wait' })
  }
})
