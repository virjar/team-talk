import { expect, test } from '@playwright/test'

function heldResponse() {
  let release!: () => void
  const ready = new Promise<void>(resolve => { release = resolve })
  return { ready, release }
}

test.beforeEach(async ({ page }) => {
  // Only the loopback fixture sees this dummy token; every API request is intercepted below.
  await page.addInitScript(() => localStorage.setItem('tt-admin-token', 'browser-fixture'))
})

for (const kind of ['users', 'groups'] as const) {
  test(`${kind}: pagination, stale responses and a closed detail share the query lifetime`, async ({ page }, info) => {
    const stale = heldResponse()
    const staleDelivered = heldResponse()
    const latest = heldResponse()
    const detail = heldResponse()
    const requests: URL[] = []
    let detailStarted = false
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url())
      if (url.pathname === `/api/admin/${kind}`) {
        requests.push(url)
        const query = url.searchParams.get('query') ?? ''
        const pageNumber = Number(url.searchParams.get('page'))
        if (query === 'stale') {
          await stale.ready
          await route.fulfill({ status: 500, json: { error: 'stale failure must stay hidden' } })
          staleDelivered.release()
          return
        }
        if (query === 'latest') await latest.ready
        const name = `fixture-${query || 'all'}-page-${pageNumber}`
        const row = kind === 'users'
          ? { uid: 'fixture-user', username: name, name, status: 1 }
          : { chatId: 'fixture-chat', name, memberCount: 1, mutedAll: false }
        await route.fulfill({ json: { total: 40, items: [row] } })
      } else if (url.pathname.startsWith(`/api/admin/${kind}/`)) {
        detailStarted = true
        await detail.ready
        await route.fulfill({ json: kind === 'users'
          ? { user: { uid: 'fixture-user', name: 'late-detail' }, devices: [], friends: [], groups: [], online: false }
          : { chat: { chatId: 'fixture-chat', name: 'late-detail' }, members: [] } })
      } else {
        await route.fulfill({ status: 404, json: { error: 'Unexpected fixture request' } })
      }
    })

    try {
      await page.goto(`/admin/${kind}`)
      await expect(page.getByRole('row').filter({ hasText: 'fixture-all-page-1' })).toBeVisible()
      await page.getByTitle('2', { exact: true }).click()
      await expect(page.getByRole('row').filter({ hasText: 'fixture-all-page-2' })).toBeVisible()
      const beforeSearch = requests.length
      const input = page.getByPlaceholder(kind === 'users' ? '用户名/昵称/UID' : '群名')
      await input.press('Enter')
      await expect(page.getByRole('row').filter({ hasText: 'fixture-all-page-1' })).toBeVisible()
      expect(requests.slice(beforeSearch).map(url => url.searchParams.get('page'))).toEqual(['1'])

      await input.fill('stale')
      await expect.poll(() => requests.at(-1)?.searchParams.get('query')).toBe('stale')
      await input.fill('latest')
      await expect.poll(() => requests.at(-1)?.searchParams.get('query')).toBe('latest')
      stale.release()
      await staleDelivered.ready
      // Completing the superseded request must not stop the still-pending query's spinner.
      await expect(page.locator('.ant-spin-spinning')).toBeVisible()
      await expect(page.getByText('stale failure must stay hidden')).toHaveCount(0)
      latest.release()
      await expect(page.getByRole('row').filter({ hasText: 'fixture-latest-page-1' })).toBeVisible()

      await page.getByRole('button', { name: kind === 'users' ? /^详\s*情$/ : /^成\s*员$/ }).click()
      await expect.poll(() => detailStarted).toBe(true)
      await page.getByRole('button', { name: '关闭', exact: true }).click()
      detail.release()
      await expect(page.getByRole('dialog')).toHaveCount(0)
      await expect(page.getByText('late-detail', { exact: true })).toHaveCount(0)
      await page.screenshot({ path: info.outputPath(`${kind}.png`) })
    } finally {
      stale.release()
      latest.release()
      detail.release()
      await page.unrouteAll({ behavior: 'wait' })
    }
  })
}

test('release refresh after a mutation uses the currently selected client', async ({ page }, info) => {
  const mutation = heldResponse()
  const requests: string[] = []
  let mutationStarted = false
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/admin/client-releases') {
      const client = url.searchParams.get('client') ?? 'desktop'
      requests.push(client)
      await route.fulfill({ json: { releases: [{
        id: client === 'desktop' ? 1 : 2, clientType: client, platform: client === 'desktop' ? 'macos' : 'android',
        arch: 'amd64', version: `fixture-${client}`, build: 1, channel: 'snapshot', status: 'DISABLED',
        forced: false, fileCount: 1, totalBytes: 1024, createdAt: 1, createdBy: 'fixture',
      }] } })
    } else if (url.pathname === '/api/admin/client-channels') {
      await route.fulfill({ json: { channels: [] } })
    } else if (url.pathname === '/api/admin/client-releases/1/enable') {
      mutationStarted = true
      await mutation.ready
      await route.fulfill({ json: {} })
    } else {
      await route.fulfill({ status: 404, json: { error: 'Unexpected fixture request' } })
    }
  })
  try {
    await page.goto('/admin/releases')
    await expect(page.getByRole('cell', { name: 'vfixture-desktop (1)', exact: true })).toBeVisible()
    await page.getByText('启用', { exact: true }).click()
    await expect.poll(() => mutationStarted).toBe(true)
    await page.getByRole('combobox').click()
    await page.getByTitle('Android', { exact: true }).click()
    await expect(page.getByRole('cell', { name: 'vfixture-android (1)', exact: true })).toBeVisible()
    const beforeCompletion = requests.length
    mutation.release()
    await expect.poll(() => requests.length).toBeGreaterThan(beforeCompletion)
    expect(requests.slice(beforeCompletion)).toEqual(['android'])
    await expect(page.getByRole('cell', { name: 'vfixture-android (1)', exact: true })).toBeVisible()
    await expect(page.getByRole('cell', { name: 'vfixture-desktop (1)', exact: true })).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('releases.png') })
  } finally {
    mutation.release()
    await page.unrouteAll({ behavior: 'wait' })
  }
})
