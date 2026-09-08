import { createRequire } from 'node:module'

/**
 * 解析 playwright。
 *
 * 优先按常规方式解析(本地 devDependency);解析不到再退到全局安装 ——
 * ESM 不认 NODE_PATH,所以全局包必须用 createRequire 桥接。
 * 全局目录用 `npm root -g` 的结果覆盖:DG_PLAYWRIGHT_ROOT=$(npm root -g)
 */
async function resolveChromium() {
  try {
    return (await import('playwright')).chromium
  } catch {
    const globalRoot = process.env.DG_PLAYWRIGHT_ROOT
    if (!globalRoot) {
      console.error(
        '找不到 playwright。请在 frontend 目录安装,或设置 DG_PLAYWRIGHT_ROOT=$(npm root -g)',
      )
      process.exit(2)
    }
    return createRequire(globalRoot.endsWith('/') ? globalRoot : globalRoot + '/')('playwright')
      .chromium
  }
}

const chromium = await resolveChromium()

// 地址与口令从环境变量读,不硬编码 —— 这个脚本会进版本库
const BASE = process.env.DG_UI_BASE ?? 'http://127.0.0.1:4173'
const PASSWORD = process.env.DG_ADMIN_PASSWORD
if (!PASSWORD) {
  console.error('请设置 DG_ADMIN_PASSWORD(应用启动时用的管理员口令)')
  process.exit(2)
}
const results = []
const record = (label, ok, detail = '') => {
  results.push({ label, ok, detail })
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${label}${detail ? '  — ' + detail : ''}`)
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })

const consoleErrors = []
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', (e) => consoleErrors.push(String(e)))

try {
  // ── 登录 ────────────────────────────────────────────────────────────
  console.log('\n【UI】登录')
  await page.goto(BASE + '/', { waitUntil: 'networkidle' })
  record('未登录时被重定向到登录页', page.url().includes('/login'), page.url())

  await page.fill('input[autocomplete="username"]', 'admin')
  await page.fill('input[autocomplete="current-password"]', PASSWORD)
  await page.click('button[type="submit"]')
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 })
  record('登录成功并真正离开登录页', !new URL(page.url()).pathname.startsWith('/login'), page.url())

  // ── 菜单来自后端 ────────────────────────────────────────────────────
  console.log('\n【UI】布局与菜单')
  const menuText = await page.locator('.layout__menu').innerText()
  record('侧边栏渲染出后端下发的菜单', menuText.includes('数据源'), menuText.replace(/\s+/g, ' ').slice(0, 60))
  const wsSelector = await page.locator('.layout__header-right .el-select').count()
  record('顶栏有空间切换器', wsSelector > 0)

  // ── 数据源列表 ──────────────────────────────────────────────────────
  console.log('\n【UI】数据源列表')
  await page.goto(BASE + '/metadata/datasources', { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__row', { timeout: 15000 })
  const rowCount = await page.locator('.el-table__row').count()
  record('数据源列表有数据', rowCount > 0, `${rowCount} 行`)

  const catalogPanel = await page.locator('.catalog-panel').innerText()
  record('左侧渲染数据源目录树(功能5)', catalogPanel.includes('全部') && catalogPanel.includes('未分类'),
    catalogPanel.replace(/\s+/g, ' ').slice(0, 60))

  const tagsText = await page.locator('.el-table__body .el-tag').first().innerText()
  record('状态列渲染为标签', tagsText.length > 0, tagsText)

  // ── 结构浏览抽屉 ────────────────────────────────────────────────────
  console.log('\n【UI】库表结构浏览(功能7)')
  // 找一行状态为「可用」的数据源,点它的「结构」按钮
  const availableRow = page.locator('.el-table__row').filter({ hasText: '可用' }).first()
  const hasAvailable = (await availableRow.count()) > 0
  if (hasAvailable) {
    await availableRow.getByRole('button', { name: '结构' }).click()
    await page.waitForSelector('.el-drawer', { state: 'visible', timeout: 10000 })
    await page.waitForSelector('.el-drawer .el-tree-node', { timeout: 15000 })
    const treeText = await page.locator('.el-drawer .browser__tree').innerText()
    record('抽屉打开并加载出库层级', treeText.length > 0, treeText.replace(/\s+/g, ' ').slice(0, 60))

    // 下钻:点第一个库
    await page.locator('.el-drawer .el-tree-node__content').first().click()
    await page.waitForTimeout(2500)
    const afterExpand = await page.locator('.el-drawer .el-tree-node').count()
    record('点击库节点后有下钻', afterExpand >= 1, `${afterExpand} 个节点`)
    await page.keyboard.press('Escape')
  } else {
    record('抽屉打开并加载出库层级', false, '列表里没有「可用」状态的数据源')
  }

  // ── 新建对话框按能力渲染 ────────────────────────────────────────────
  console.log('\n【UI】新建数据源表单按类型动态渲染')
  await page.getByRole('button', { name: '新建数据源' }).click()
  await page.waitForSelector('.el-dialog', { state: 'visible' })

  await page.locator('.el-dialog .el-select').first().click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  const options = await page.locator('.el-select-dropdown__item:visible').allInnerTexts()
  record('类型下拉来自 /datasource-types', options.length >= 5, `${options.length} 项`)

  // 选 RestAPI —— 它应该显示 baseUrl 而不是主机/端口
  const restOption = page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'RestAPI' }).first()
  if (await restOption.count()) {
    await restOption.click()
    await page.waitForTimeout(600)
    const dialogText = await page.locator('.el-dialog').innerText()
    record('选 RestAPI 后表单显示「接口地址」而非「主机」',
      dialogText.includes('接口地址') && !dialogText.includes('直填 JDBC URL'))
  }

  // 换成 MySQL —— 应出现主机/端口且端口自动填 3306
  await page.locator('.el-dialog .el-select').first().click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'MySQL' }).first().click()
  await page.waitForTimeout(600)
  const dialogText2 = await page.locator('.el-dialog').innerText()
  record('选 MySQL 后表单显示主机/端口/库名', dialogText2.includes('主机') && dialogText2.includes('库名'))
  const portValue = await page.locator('.el-dialog .el-input-number input').first().inputValue()
  record('切换类型自动填默认端口', portValue === '3306', `端口=${portValue}`)

  // MySQL 是关系型,不该出现节点编辑器 —— 多节点只对 MPP 开放(功能2)
  record('非 MPP 类型不显示节点编辑器', !dialogText2.includes('其它 FE 节点'))

  // 换成 Doris —— 节点编辑器应该出现,且能加行
  await page.locator('.el-dialog .el-select').first().click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'Apache Doris' }).first().click()
  await page.waitForTimeout(600)
  const dorisText = await page.locator('.el-dialog').innerText()
  record('选 Doris 后出现多节点编辑器(功能2)', dorisText.includes('其它 FE 节点'))

  const addNode = page.locator('.el-dialog button').filter({ hasText: '添加节点' }).first()
  if (await addNode.count()) {
    const before = await page.locator('.el-dialog .props__row').count()
    await addNode.click()
    await page.waitForTimeout(300)
    const after = await page.locator('.el-dialog .props__row').count()
    record('点「添加节点」能新增一行', after === before + 1, `${before} → ${after} 行`)
  } else {
    record('点「添加节点」能新增一行', false, '找不到按钮')
  }

  await page.screenshot({ path: `${process.env.DG_UI_SHOT_DIR ?? '/tmp'}/ui-form.png` })
  await page.keyboard.press('Escape')

  // ── 空间启停(功能28)────────────────────────────────────────────────
  console.log('\n【UI】空间管理')
  await page.goto(BASE + '/settings/workspaces', { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  const wsText = await page.locator('.el-table').innerText()
  record('空间列表渲染状态列', wsText.includes('启用') || wsText.includes('停用'), wsText.split('\n')[0])

  const toggle = page.locator('.el-table button').filter({ hasText: /^(停用|启用)$/ }).first()
  record('每个空间有启用/停用按钮(功能28)', (await toggle.count()) > 0)

  // 停用要二次确认 —— 它会让空间内所有成员立刻失去访问,不该一键生效
  if ((await toggle.count()) > 0 && (await toggle.innerText()) === '停用') {
    await toggle.click()
    await page.waitForTimeout(600)
    const confirmVisible = await page.locator('.el-message-box').isVisible().catch(() => false)
    record('停用前弹出二次确认', confirmVisible)
    if (confirmVisible) {
      await page.locator('.el-message-box button').filter({ hasText: '取消' }).first().click()
      await page.waitForTimeout(400)
    }
  }

  // ── 任务管理与执行记录(P2)──────────────────────────────────────────
  console.log('\n【UI】任务管理(功能 9-16)')
  await page.goto(BASE + '/integration/jobs', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)

  const jobsText = await page.locator('.page-container').innerText()
  record('任务管理页可访问', jobsText.includes('新建任务'), jobsText.split('\n')[0])

  // 类型下拉必须来自后端 —— 前端不硬编码七种任务类型
  await page.locator('button:has-text("新建任务")').first().click()
  await page.waitForSelector('.el-dialog', { state: 'visible' })
  await page.locator('.el-dialog .el-select').nth(0).click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  const jobTypeOptions = await page.locator('.el-select-dropdown__item:visible').allInnerTexts()
  record('任务类型下拉来自 /jobs/types', jobTypeOptions.length >= 7, `${jobTypeOptions.length} 项`)
  record('下拉里标注了「可周期调度 / 常驻 / 一次性」',
    jobTypeOptions.some((t) => t.includes('一次性') || t.includes('常驻')),
    jobTypeOptions.slice(0, 3).join(' | '))

  // 选离线同步 —— 应出现字段映射编辑器
  const syncOption = page.locator('.el-select-dropdown__item:visible').filter({ hasText: '离线同步' }).first()
  if (await syncOption.count()) {
    await syncOption.click()
    await page.waitForTimeout(600)
    const dialogText = await page.locator('.el-dialog').innerText()
    record('选离线同步后出现字段映射编辑器', dialogText.includes('字段映射'))
    record('出现写入模式选择', dialogText.includes('写入模式'))
  }

  // 换成整库迁移 —— 应出现表名规则与自动建表(功能 9)
  await page.locator('.el-dialog .el-select').nth(0).click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  const migrationOption = page.locator('.el-select-dropdown__item:visible').filter({ hasText: '整库迁移' }).first()
  if (await migrationOption.count()) {
    await migrationOption.click()
    await page.waitForTimeout(600)
    const dialogText = await page.locator('.el-dialog').innerText()
    record('选整库迁移后出现表名规则与自动建表(功能9)',
      dialogText.includes('表名规则') && dialogText.includes('自动建表'))
    record('整库迁移不显示字段映射 —— 目标表还不存在,没有可映射的一端',
      !dialogText.includes('字段映射'))
  }
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)

  console.log('\n【UI】执行记录(序号 10/15/19/21/23 共用)')
  await page.goto(BASE + '/ops/executions', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)

  const execText = await page.locator('.page-container').innerText()
  record('执行记录页说明了「五个页面查同一张表」',
    execText.includes('同一张执行事实表'), execText.split('\n')[0])

  const execRows = await page.locator('.el-table__body tr').count()
  record('执行记录有数据', execRows > 0, `${execRows} 行`)

  // 作业种类筛选器由后端下发,不硬编码五个菜单项
  await page.locator('.page-toolbar__filters .el-select').first().click()
  await page.waitForSelector('.el-select-dropdown__item:visible', { state: 'visible' })
  const refTypes = await page.locator('.el-select-dropdown__item:visible').allInnerTexts()
  record('作业种类筛选来自 /executions/job-types', refTypes.length >= 10, `${refTypes.length} 种`)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)

  // 详情要能看到尝试列表 —— 重试不新建执行记录,只新增尝试
  const firstDetail = page.locator('.el-table__body tr button:has-text("详情")').first()
  if (await firstDetail.count()) {
    await firstDetail.click()
    await page.waitForSelector('.el-drawer', { state: 'visible' })
    await page.waitForTimeout(600)
    const drawerText = await page.locator('.el-drawer').innerText()
    record('执行详情显示尝试列表(重试不新建执行记录)', drawerText.includes('尝试'))
    record('执行详情显示定义版本 —— 可复现性的依据', drawerText.includes('定义版本'))
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
  } else {
    record('执行详情显示尝试列表(重试不新建执行记录)', false, '没有可点的详情按钮')
    record('执行详情显示定义版本 —— 可复现性的依据', false, '没有可点的详情按钮')
  }

  // ── 控制台无报错 ────────────────────────────────────────────────────
  console.log('\n【UI】运行时健康')
  const realErrors = consoleErrors.filter((e) => !e.includes('favicon'))
  record('浏览器控制台无 JS 报错', realErrors.length === 0, realErrors.slice(0, 2).join(' | '))

  await page.goto(BASE + '/metadata/datasources', { waitUntil: 'networkidle' })
  await page.screenshot({
    path: `${process.env.DG_UI_SHOT_DIR ?? '/tmp'}/ui-list.png`,
    fullPage: true,
  })
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log('\n' + '='.repeat(66))
console.log(failed.length ? ` UI 验证:${failed.length} 项未通过` : ' UI 验证:全部通过')
console.log('='.repeat(66))
process.exit(failed.length ? 1 : 0)
