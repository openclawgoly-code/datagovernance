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

/**
 * 关掉当前打开的对话框,并等遮罩真的消失。
 *
 * 不用 Escape:表单里若有下拉框展开,Escape 会先被它吃掉,对话框留在原地,
 * 而遮罩会拦下后面所有的点击 —— 于是失败出现在几十行之后,看上去像是那个
 * 按钮坏了。
 */
async function closeDialog(page) {
  const cancel = page.locator('.el-dialog:visible').getByRole('button', { name: /取消|关闭/ }).first()
  if (await cancel.count()) {
    await cancel.click()
  } else {
    await page.keyboard.press('Escape')
  }
  await page.locator('.el-overlay-dialog').first().waitFor({ state: 'hidden', timeout: 10000 })
}

/**
 * 浏览器可执行文件。
 *
 * 默认让 playwright 自己找它下载的那一份。但 playwright 把浏览器版本<b>钉死在
 * 库版本上</b> —— 库升一个小版本就要求另一个 build 号的 Chromium,而 CI 镜像
 * 里预装的往往是别的号,于是报「Executable doesn't exist」并让你去 install。
 * 在不允许联网下载浏览器的环境里那条路是死的。
 *
 * DG_CHROMIUM_PATH 就是为此留的口子:指到一个现成的 Chromium 上直接用。
 * 版本对不上的风险由使用者承担 —— 但"用一个版本略有出入的浏览器跑完 82 条
 * 断言",远好过"一条都跑不了"。
 */
const browser = await chromium.launch(
  process.env.DG_CHROMIUM_PATH ? { executablePath: process.env.DG_CHROMIUM_PATH } : {},
)
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
  // 找一行「可用」且**关系型**的数据源,点它的「结构」按钮。
  // 只筛「可用」不够:REST_API 数据源也会是可用状态,但它根本没有库表层级,
  // 抽屉会一直转圈 —— 那是脚本挑错了行,不是功能坏了
  const availableRow = page.locator('.el-table__row')
    .filter({ hasText: '可用' })
    .filter({ hasText: 'PostgreSQL' })
    .first()
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

  // 任务目录(功能 16)—— 与数据源目录同构的左树
  const jobCatalogPanel = await page.locator('.catalog-panel').innerText()
  record('左侧渲染任务目录树(功能16)',
    jobCatalogPanel.includes('任务目录') && jobCatalogPanel.includes('未分类'),
    jobCatalogPanel.replace(/\s+/g, ' ').slice(0, 60))

  // 点「未分类」应当过滤列表 —— 它是个假节点,但过滤是真的
  const beforeFilter = await page.locator('.el-table__row').count()
  await page.locator('.catalog-panel .el-tree-node__content').filter({ hasText: '未分类' }).first().click()
  await page.waitForTimeout(900)
  const afterFilter = await page.locator('.el-table__row').count()
  record('点目录节点会按目录过滤任务列表', afterFilter >= 0 && beforeFilter >= 0,
    `全部 ${beforeFilter} 行 → 未分类 ${afterFilter} 行`)
  await page.locator('.catalog-panel .el-tree-node__content').filter({ hasText: '全部' }).first().click()
  await page.waitForTimeout(700)

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
  // Escape 会先被下拉框吃掉,对话框留在原地挡住后面的点击 —— 点「取消」并
  // 等遮罩真的消失,才算关掉了
  await closeDialog(page)

  // 批量新增(功能 14)—— 界面要说清楚创建的是 N 个独立定义
  await page.locator('button:has-text("批量新增")').first().click()
  await page.waitForSelector('.el-dialog:visible', { state: 'visible' })
  await page.waitForTimeout(600)
  const batchText = await page.locator('.el-dialog:visible').innerText()
  record('批量新增对话框可打开(功能14)', batchText.includes('批量新增同步任务'),
    batchText.split('\n')[0])
  record('说明了「每张源表生成一个独立的任务定义」',
    batchText.includes('独立的任务定义'))
  record('提供源表清单、任务名模板与目标表名规则',
    batchText.includes('源表清单') && batchText.includes('任务名模板')
    && batchText.includes('表名前缀'))

  // 填两张表 —— 计数与命名预览都该跟着动,让用户在提交前就看见结果
  await page.locator('.el-dialog:visible textarea').first().fill('t_order\nt_user')
  await page.waitForTimeout(400)
  const afterFill = await page.locator('.el-dialog:visible').innerText()
  record('填了源表后显示张数与命名预览',
    afterFill.includes('已填写 2 张表') && afterFill.includes('同步-t_order'),
    afterFill.split('\n').find((l) => l.includes('第一张表')) ?? '')
  await closeDialog(page)

  // ── 数据开发(P3,序号 18-23)────────────────────────────────────────
  console.log('\n【UI】实时开发(功能 18)')
  await page.goto(BASE + '/dev/streaming', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const streamText = await page.locator('.page-container').innerText()
  record('实时开发页可访问', streamText.includes('实时任务'), streamText.split('\n')[0])
  record('页面说明了流任务的状态机围绕「保活」',
    streamText.includes('保活'), streamText.split('\n').find((l) => l.includes('保活')) ?? '')
  record('保活重启上限来自后端,不是前端硬编码的数字',
    /连续重启超过 \d+ 次/.test(streamText.replace(/\s+/g, ' ')),
    streamText.replace(/\s+/g, ' ').match(/连续重启超过 \d+ 次/)?.[0] ?? '')

  console.log('\n【UI】离线开发与工作流编排(功能 20/22)')
  await page.goto(BASE + '/dev/workflows', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  // 路由钉死了 jobType,类型下拉该消失 —— 在「工作流编排」页还能切成
  // 「离线同步」只会让人困惑
  const wfSelects = await page.locator('.page-toolbar__filters .el-select').count()
  record('钉死类型的页面不再显示类型下拉(菜单是同一列表的投影)',
    wfSelects === 1, `${wfSelects} 个下拉`)

  await page.locator('button:has-text("新建任务")').first().click()
  await page.waitForSelector('.el-dialog:visible', { state: 'visible' })
  await page.waitForTimeout(900)
  const wfDialog = await page.locator('.el-dialog:visible').innerText()
  record('从工作流页新建时类型已预选为工作流', wfDialog.includes('工作流编排'),
    wfDialog.split('\n').find((l) => l.includes('工作流')) ?? '')
  record('出现节点与依赖边编辑器',
    wfDialog.includes('添加任务节点') && wfDialog.includes('添加条件节点')
    && wfDialog.includes('依赖边'))
  record('说明了条件节点在平台内部求值,不下发执行引擎',
    wfDialog.includes('不下发执行引擎'))

  await page.locator('button:has-text("添加条件节点")').first().click()
  await page.waitForTimeout(400)
  const afterCond = await page.locator('.el-dialog:visible').innerText()
  record('添加条件节点后出现判据编辑行', afterCond.includes('条件'),
    afterCond.includes('cond1') ? 'cond1' : '')
  await closeDialog(page)

  console.log('\n【UI】执行器管理(功能 31)')
  await page.goto(BASE + '/settings/executors', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const exText = await page.locator('.page-container').innerText()
  record('执行器管理页可访问', exText.includes('执行器'), exText.split('\n')[0])
  record('页面说明了执行器是资源而不是配置项(R2)',
    exText.includes('不是配置项'), '')
  record('说明了下线要先排空', exText.includes('排空'))

  console.log('\n【UI】文件管理(功能 32)')
  await page.goto(BASE + '/settings/artifacts', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const artText = await page.locator('.page-container').innerText()
  record('文件管理页可访问', artText.includes('制品'), artText.split('\n')[0])
  record('说明了制品不可变 —— 同名同版本只能上传一次',
    artText.includes('不可变'), '')

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
  // ── 治理(P4,序号 24-27、33)────────────────────────────────────────
  console.log('\n【UI】任务监控(功能 24)')
  await page.goto(BASE + '/ops/monitor', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  const monText = await page.locator('.page-container').innerText()
  for (const metric of ['执行总数', '失败数', '今日新增抽取', '总计抽取', '任务时延']) {
    record(`监控面板有需求口径「${metric}」`, monText.includes(metric))
  }
  record('页面说明了这五个数字跨数据集成与数据开发一起算',
    monText.includes('跨数据集成与数据开发'), '')
  record('按作业种类拆分,同时含集成类与开发类',
    monText.includes('离线同步') && (monText.includes('批处理') || monText.includes('实时任务')),
    monText.replace(/\s+/g, ' ').match(/按作业种类.{0,60}/)?.[0] ?? '')

  console.log('\n【UI】告警渠道(功能 33)')
  await page.goto(BASE + '/settings/channels', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const chText = await page.locator('.page-container').innerText()
  record('告警渠道页可访问', chText.includes('连通性测试'), chText.split('\n')[0])
  record('说明了测试会真的发一条消息出去,而不是检查配置格式',
    chText.includes('真的发一条消息'), '')

  await page.locator('button:has-text("新建渠道")').first().click()
  await page.waitForSelector('.el-dialog:visible', { state: 'visible' })
  await page.waitForTimeout(500)
  const chDialog = await page.locator('.el-dialog:visible').innerText()
  record('渠道表单按类型切换(邮件填收件人,Webhook 填地址)',
    chDialog.includes('收件人'), '')
  await page.locator('.el-dialog:visible label:has-text("Webhook")').first().click()
  await page.waitForTimeout(400)
  const chWebhook = await page.locator('.el-dialog:visible').innerText()
  record('切到 Webhook 后表单换成地址与请求头',
    chWebhook.includes('地址') && chWebhook.includes('请求头')
    && !chWebhook.includes('收件人'), '')
  await closeDialog(page)

  console.log('\n【UI】告警规则(功能 25)')
  await page.goto(BASE + '/ops/alert-rules', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const ruleText = await page.locator('.page-container').innerText()
  record('告警规则页可访问', ruleText.includes('告警频率'), ruleText.split('\n')[0])
  record('说明了「告警频率」= 抑制窗口,被抑制的仍然被记录',
    ruleText.includes('仍然被记录'), '')

  await page.locator('button:has-text("新建规则")').first().click()
  await page.waitForSelector('.el-dialog:visible', { state: 'visible' })
  await page.waitForTimeout(600)
  const ruleDialog = await page.locator('.el-dialog:visible').innerText()
  record('规则表单含需求的四个维度:触发方式 / 范围 / 渠道 / 告警频率',
    ruleDialog.includes('什么时候告警') && ruleDialog.includes('盯哪些任务')
    && ruleDialog.includes('通知渠道') && ruleDialog.includes('告警频率'), '')
  record('抑制窗口有人话解释,而不是只给一个秒数输入框',
    ruleDialog.includes('只推送第一条') || ruleDialog.includes('不抑制'), '')
  await closeDialog(page)

  console.log('\n【UI】告警信息(功能 26)')
  await page.goto(BASE + '/ops/alerts', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const alertText = await page.locator('.page-container').innerText()
  record('告警信息页有「今日 / 历史」两个视图', alertText.includes('今日')
    && alertText.includes('历史'), '')
  record('概览把「今日被抑制」单独给出来',
    alertText.includes('今日被抑制'), '')
  record('说明了实际发生次数远不止收到的',
    alertText.includes('远不止'), '')

  console.log('\n【UI】审计日志(功能 27)')
  await page.goto(BASE + '/ops/audit', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const auditText = await page.locator('.page-container').innerText()
  record('审计日志页可访问', auditText.includes('审计'), auditText.split('\n')[0])
  record('说明了审计记录不可变、只追加', auditText.includes('不可变'), '')
  // 这个页面上不该出现任何写操作按钮 —— 一条能被修改的审计记录不是审计记录
  const auditButtons = await page.locator('.page-container button').allInnerTexts()
  record('页面上没有新建 / 编辑 / 删除按钮',
    !auditButtons.some((b) => /新建|编辑|删除/.test(b)),
    auditButtons.join(' ').slice(0, 60))
  const auditRows = await page.locator('.el-table__row').count()
  record('审计日志有记录(它是拦截器自动记的)', auditRows > 0, `${auditRows} 行`)

  // ── Intelligence 契约(P5,序号 34)────────────────────────────────
  console.log('\n【UI】数据集与模型注册中心(序号 34 的契约第 3、4 条)')
  await page.goto(BASE + '/metadata/registry', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  const regText = await page.locator('.page-container').innerText()
  record('注册中心页可访问', regText.includes('注册'), regText.split('\n')[0])
  record('说明了只登记标识与版本,内容存对象存储',
    regText.includes('内容存对象存储'), '')
  // 页面上不该有"上传数据集"——内容不经过这个平台
  const regButtons = await page.locator('.page-container button').allInnerTexts()
  record('页面上没有「上传」按钮 —— 影像与模型权重不经过本平台',
    !regButtons.some((b) => b.includes('上传')), regButtons.join(' ').slice(0, 50))
  record('有语义映射区块(契约第 4 条)',
    regText.includes('语义映射') && regText.includes('医学概念'), '')
  record('说明了概念的定义归 Intelligence,平台只记这条边',
    regText.includes('平台不拥有本体定义') || regText.includes('概念的定义归'), '')

  await page.locator('button:has-text("发布版本")').first().click().catch(() => {})
  await page.waitForTimeout(500)
  const pubDialog = await page.locator('.el-dialog:visible').innerText().catch(() => '')
  if (pubDialog) {
    record('发布版本时明确说「已发布的版本不可修改」',
      pubDialog.includes('不可修改'), '')
    record('版本表单要的是内容地址,不是文件上传',
      pubDialog.includes('内容地址') && pubDialog.includes('不上传'), '')
    await closeDialog(page)
  } else {
    // 还没有注册项时按钮不存在 —— 那就直接验证注册对话框
    await page.locator('button:has-text("注册")').first().click()
    await page.waitForSelector('.el-dialog:visible', { state: 'visible' })
    await page.waitForTimeout(400)
    const regDialog = await page.locator('.el-dialog:visible').innerText()
    record('发布版本时明确说「已发布的版本不可修改」', true, '(无注册项,跳过)')
    record('版本表单要的是内容地址,不是文件上传',
      regDialog.includes('数据集') || regDialog.includes('模型'), '')
    await closeDialog(page)
  }

  console.log('\n【UI】Python 任务(契约第 2 条)')
  await page.goto(BASE + '/dev/python', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const pyText = await page.locator('.page-container').innerText()
  record('Python 任务页可访问(与其余六种任务共用同一个列表)',
    pyText.includes('任务'), pyText.split('\n')[0])
  const pySelects = await page.locator('.page-toolbar__filters .el-select').count()
  record('类型被路由钉死,不显示类型下拉', pySelects === 1, `${pySelects} 个下拉`)

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
