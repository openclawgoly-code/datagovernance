import { createRequire } from 'node:module'
const require = createRequire(process.env.DG_PLAYWRIGHT_ROOT.replace(/\/?$/, '/'))
const { chromium } = require('playwright')

const BASE = 'http://127.0.0.1:4173'
const PW = process.env.DG_ADMIN_PASSWORD
// 这个脚本的产出就是截图,没有 SHOT_DIR 就没有意义 —— 早点说清楚,
// 否则它会安静地往 ./undefined/ 里丢二十张图
const DIR = process.env.SHOT_DIR
if (!DIR) {
  console.error('请设置 SHOT_DIR(截图输出目录)')
  process.exit(1)
}
let n = 0
const shots = []

const browser = await chromium.launch({ executablePath: process.env.DG_CHROMIUM_PATH })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })

async function shot(name, caption, opts = {}) {
  n += 1
  const file = `${DIR}/${String(n).padStart(2, '0')}-${name}.png`
  await page.waitForTimeout(500)
  await page.screenshot({ path: file, fullPage: opts.full ?? false })
  shots.push({ n, name, caption })
  console.log(`  ${String(n).padStart(2, '0')} ${caption}`)
}

// 有些步骤依赖界面上恰好存在某一行/某个按钮。缺了就跳过而不是整轮中断 ——
// 一次跑拿到 13 张总好过因为第 4 步没匹配上就什么都没有。
async function step(label, fn) {
  try {
    await fn()
  } catch (e) {
    console.log(`  ✗ ${label} 跳过: ${String(e).split('\n')[0].slice(0, 90)}`)
    // 失败往往是在对话框/抽屉打开着的时候发生的。不清掉遮罩,后面每一次
    // 点击都会超时 —— 一个问题看起来会像五个。
    for (let i = 0; i < 3; i++) {
      await page.keyboard.press('Escape').catch(() => {})
      await page.waitForTimeout(300)
    }
    await page.mouse.click(5, 5).catch(() => {})
    await page.waitForTimeout(400)
  }
}

async function closeDialog() {
  const cancel = page.locator('.el-dialog:visible').getByRole('button', { name: /取消|关闭/ }).first()
  if (await cancel.count()) await cancel.click()
  else await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
}

console.log('\n开始走真实操作流程\n')

// ── 1. 登录 ───────────────────────────────────────────────────────────
await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await shot('login', '登录页 —— 未登录访问任何地址都会被重定向到这里')

await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', PW)
await shot('login-filled', '填入账号口令')

await page.click('button[type="submit"]')
await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 20000 })
await page.waitForTimeout(1200)
await shot('workbench', '登录后进入工作台,左侧菜单由后端按权限下发')

// ── 2. 数据源 ─────────────────────────────────────────────────────────
await page.goto(BASE + '/metadata/datasources', { waitUntil: 'networkidle' })
await page.waitForSelector('.el-table__row', { timeout: 20000 })
await shot('datasource-list', '数据源列表 —— 六种类型,状态列显示是否已测通')

await step('新建数据源表单', async () => {
  await page.getByRole('button', { name: '新建数据源' }).click()
  await page.waitForSelector('.el-dialog', { state: 'visible' })
  await page.locator('.el-dialog .el-select').first().click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'MySQL' }).first().click()
  await page.waitForTimeout(600)
  await shot('datasource-form', '新建数据源 —— 表单按所选类型渲染,端口自动填 3306')

  await page.locator('.el-dialog .el-select').first().click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'RestAPI' }).first().click()
  await page.waitForTimeout(600)
  await shot('datasource-form-rest', '换成 RestAPI —— 主机端口消失,改为「接口地址」')
  await closeDialog()
})

await step('测试连接', async () => {
  await page.goto(BASE + '/metadata/datasources', { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__row', { timeout: 20000 })
  const row = page.locator('.el-table__row').filter({ hasText: 'PostgreSQL' }).first()
  await row.getByRole('button', { name: '测试' }).click()
  // 不等那个 3 秒后自动消失的 toast —— 抓它太看运气,而结果本来就写在列表的状态列里
  await page.waitForTimeout(3500)
  await shot('test-connection', '点「测试」会真的去连目标库;结果写回状态列与「最近测试」')
})

await step('结构浏览', async () => {
  await page.goto(BASE + '/metadata/datasources', { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__row', { timeout: 20000 })
  // 指名道姓选 Pagila。随便挑一个"可用的 PostgreSQL"会挑到数仓库,
  // 那里只有平台自己的内部表 —— 截出来跟说明文字对不上,是最坏的一种截图。
  const row = page.locator('.el-table__row').filter({ hasText: 'Pagila' }).first()
  await row.getByRole('button', { name: '结构' }).click()
  await page.waitForSelector('.el-drawer', { state: 'visible', timeout: 15000 })
  await page.waitForSelector('.el-drawer .el-tree-node', { timeout: 25000 })
  await shot('catalog-drawer', '结构浏览 —— 第一层是库,每一层都真的去目标库探,不是缓存的猜测')

  const dbNode = page.locator('.el-drawer .el-tree-node__content').filter({ hasText: 'dg_pagila' }).first()
  if (await dbNode.count()) {
    await dbNode.click()
    await page.waitForTimeout(3000)
  }
  const schemaNode = page.locator('.el-drawer .el-tree-node__content').filter({ hasText: /^\s*public\s*$/ }).first()
  if (await schemaNode.count()) {
    await schemaNode.click()
    await page.waitForTimeout(3500)
  }
  await shot('catalog-tables', '下钻到表 —— Pagila 的 payment 是分区表,清单里只出现父表,55 个分区不列')

  const filmNode = page.locator('.el-drawer .el-tree-node__content').filter({ hasText: /^\s*film\s*$/ }).first()
  if (await filmNode.count()) {
    await filmNode.click()
    await page.waitForTimeout(2500)
    await shot('catalog-columns', '点开一张表看字段 —— 原始类型与规范类型并排,映射错了才查得出来')
  }
})

await step('自定义查询', async () => {
  await page.goto(BASE + '/metadata/datasources', { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__row', { timeout: 20000 })
  const row = page.locator('.el-table__row').filter({ hasText: 'Pagila' }).first()
  await row.getByRole('button', { name: '查询' }).click()
  await page.waitForTimeout(2500)
  const ta = page.locator('textarea').first()
  await ta.fill("SELECT film_id, title, rating, rental_rate, length\nFROM public.film\nWHERE rating = 'PG' AND length > 100\nORDER BY length DESC\nLIMIT 20")
  await page.waitForTimeout(500)
  await shot('sql-editor', '自定义 SQL 查询 —— 只允许查询类语句,写语句会被直接拒掉')

  const runBtn = page.getByRole('button', { name: /^(执行|运行)$/ }).first()
  if (await runBtn.count()) {
    await runBtn.click()
    await page.waitForTimeout(4000)
    await shot('sql-result', '查询结果 —— 强制行数上限与超时,超限会标记为「已截断」')
  }
})

// ── 3. 任务 ───────────────────────────────────────────────────────────
await page.goto(BASE + '/integration/jobs', { waitUntil: 'networkidle' })
await page.waitForTimeout(2000)
await shot('job-list', '任务管理 —— 四类任务混在一个列表,左侧按目录归类')

await step('任务详情与编译', async () => {
  const row = page.locator('.el-table__row').filter({ hasText: '影片主数据入仓' }).first()
  const detail = row.getByRole('button', { name: /详情|查看/ }).first()
  if (await detail.count()) { await detail.click() } else { await row.click() }
  await page.waitForTimeout(2500)
  await shot('job-detail', '任务详情 —— 状态、物理计划是否最新、字段映射与挂载的清洗规则', { full: true })
})

await step('运行任务', async () => {
  await page.goto(BASE + '/integration/jobs', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const row = page.locator('.el-table__row').filter({ hasText: '影片主数据入仓' }).first()
  const runBtn = row.getByRole('button', { name: /运行|执行/ }).first()
  if (await runBtn.count()) {
    await runBtn.click()
    await page.waitForTimeout(1500)
    const confirm = page.locator('.el-message-box__btns button').filter({ hasText: /确定|确认/ }).first()
    if (await confirm.count()) await confirm.click()
    await page.waitForTimeout(2500)
    await shot('job-run', '手工触发一次执行 —— 立刻生成一条执行记录')
  }
})

// ── 4. 运维 ───────────────────────────────────────────────────────────
await page.goto(BASE + '/ops/executions', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
await shot('executions', '执行记录 —— 全平台唯一的执行事实表,读写行数与耗时都在这里')

await page.goto(BASE + '/ops/monitor', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
await shot('monitor', '任务监控 —— 五个口径跨子系统统计', { full: true })

await page.goto(BASE + '/ops/alert-rules', { waitUntil: 'networkidle' })
await page.waitForTimeout(2000)
await shot('alert-rules', '告警规则 —— 五种触发方式,「告警频率」是抑制窗口')

await page.goto(BASE + '/ops/audit', { waitUntil: 'networkidle' })
await page.waitForTimeout(2000)
await shot('audit', '审计日志 —— 只追加不可改,失败的操作同样留痕')

await step('规则管理', async () => {
  await page.goto(BASE + '/integration/rules', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2000)
  await shot('rules', '规则管理 —— 九种清洗规则,脱敏规则被任务引用后删不掉')
})

console.log(`\n共 ${n} 张`)
await browser.close()
