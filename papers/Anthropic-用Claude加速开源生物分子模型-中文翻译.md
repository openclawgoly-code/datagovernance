# 用 Claude 加速开源生物分子模型

> **原题**：Accelerating open-source biomolecular models with Claude
> **发布方**：Anthropic
> **日期**：2026 年 9 月 17 日，最后更新 2026 年 9 月 21 日
> **原文 PDF**：[www-cdn.anthropic.com](https://www-cdn.anthropic.com/e96b5807039a88168733d9687afe41dfbbd5de13.pdf)
> **代码**：[github.com/anthropics/uplifting-biomolecular-modeling](https://github.com/anthropics/uplifting-biomolecular-modeling)
> **说明**：本文为全文中文翻译，正文（摘要、引言、结果、讨论、方法、致谢等）完整译出；参考文献保留英文原样；补充材料（SI）部分译出目录与总方法论，逐模型的 S1–S6 技术细节未逐条译出。

## 作者与单位

Claude Science¹,\*、Richard Shuai¹,\*、Rohil Badkundri¹、Vincent Fan¹、Kilian Fatras²、Lukas Jarosch³、Amir Shanehsazzadeh¹,†

¹ Anthropic，美国加州旧金山；² Biohub；³ 哥伦比亚大学，美国纽约

\* 共同第一作者。† 通讯作者：ashanehsazzadeh@anthropic.com

---

## 摘要

用于生物分子结构预测、蛋白质设计、基因组学和蛋白质语言建模的开源深度学习模型已被广泛使用。大规模运行这些模型成本高昂，而为最大的分子机器建模所需的 GPU 显存超过单个节点所能提供的容量。我们让 Claude 为这些模型优化推理过程。在两位具有生物分子建模经验、但并不具备推理优化或内核工程经验的科学家的指导下，Claude 在不到四周的时间里，为 36 个模型实现产出了优化包，覆盖 30 多个开源模型。每个优化包都提供最多三种模式：

- **Exact（精确）**：设计目标是逐比特复现未经修改模型的输出；
- **Fast（快速）**：以少量数值精度换取速度；
- **Big（大显存）**：降低显存占用，使更大的输入能够放入 GPU 显存。

在 NVIDIA H100 GPU 上，Exact 模式让 14 个结构预测模型的前向传播平均提速 **1.6 倍**；Fast 模式让其中具备该模式的 13 个模型平均提速 **4.1 倍**。在一个汇聚了 13 个模型配置（1,925 个"模型–靶标"配对）的生物分子界面基准上，可接受预测界面的占比在每种模式下的变化都**不到一个百分点**，且没有一项汇聚变化在统计上能与零区分开。

Claude 还开发了 **FlashPairformer v1**——一组 GPU 内核，其三角注意力（triangle attention）运算在多数此类模型所用的 pair 宽度下，平均比业界标准内核快 **2.7 倍**。

Big 模式使得在**单个 8 卡节点**上对超过 10,000 个 token（氨基酸、核苷酸，以及来自小分子和离子的原子）的复合物进行准确预测成为可能；它还在 8 块 B300 GPU 的节点上，对多达 **70,320 个残基**的蛋白组装体完成了推理（不过这些预测并不准确）。最后，Claude 设计的蛋白结合体的**计算机模拟（in silico）得分**，与那些可以使用约 100 倍 GPU 小时的早期流程相当。它使用一个大幅简化的提示词，在单块 H200 GPU 上运行 24 小时完成设计。我们公开全部优化代码。

---

## 图注

**图 1. 使用省显存的 Big 模式在单个 GPU 节点上预测的大型分子机器结构。** 从左到右：人线粒体复合物 I（PDB 9I4I）、大肠杆菌 70S 核糖体（PDB 4YBB）、磷酸酮醇酶（PDB 8IO9）。预测结果按亚基着色，并与实验结构（灰色）叠合。标注给出 token 数、所用模型与 GPU（单节点 8 卡）、TM-score，以及可接受预测界面的数量（DockQ ≥ 0.23）。这三例是图 7 中预测的一部分。

**图 2. FlashPairformer 内核加速三角注意力与三角乘法。** FlashPairformer v1 相对业界标准内核 [13] 的加速比随序列长度变化，分别针对三角注意力（一个 Pairformer block 的起止节点调用之和）与完整的三角乘法层（外向加内向）。A、B：pair 宽度 128（三角注意力为 4 头 × 32 通道），被 AlphaFold3 与 Boltz-2 使用。C、D：pair 宽度 256（8 头 × 32 通道），被 Protenix v2 使用。每个数据点是业界标准内核耗时除以 FlashPairformer v1 耗时。每个内核接收输入时已处于其自身的内存布局，因此只对内核本身的工作计时。时间为至少 20 次 CUDA graph 重放的中位数，两个内核以交替顺序、交错分块重放。全部计时为在单块 NVIDIA H100 80 GB GPU 上、batch size 为 1 的前向传播，输入未加掩码、采用 bfloat16 精度（见第 S9 节）。

**图 3. Claude 的优化加速了生物分子结构预测模型。** A：在 NVIDIA H100 GPU 上，各模式相对 default（虚线，1×）的前向传播加速比。每根柱是各基准输入尺寸（ESMFold2 为 5 个，其余模型为 7 个）上"default 前向调用耗时 / 该模式耗时"这一比值的几何平均（对 Chai-1，计的是网络本身的时间，不含特征化与输出）。∗ AlphaFold3（JAX 与 PyTorch）使用 OpenFold3-p2 权重运行。§ AlphaFold3（PyTorch）是与 default 下的 AlphaFold3（JAX）前向调用相比较。ColabFold 没有单独的 Fast 模式，其 Fast 配置与 Big 相同。B：对 A 中各模型柱取算术平均（ESMFold2 只计一次，不含 ESMFold2-Fast）；误差棒为跨模型的 95% 百分位自助法置信区间（20,000 次重采样）。

**图 4. Claude 的优化加速了涵盖 hallucination、结构生成和反向折叠的蛋白质设计模型。** 在 NVIDIA H100 GPU 上相对 default（虚线，1×）的加速比，为各基准输入尺寸上的几何平均（对 Genie 3 与 RFdiffusion，为无条件设计尺寸）。计时口径随模型族而异：hallucination 为每个设计步骤的耗时，结构生成为每 GPU 秒的设计数，反向折叠为一次设计流程的端到端墙钟时间（含模型加载等一次性开销）。hallucination 与结构生成的口径不含启动与编译时间（端到端数值见 SI）。淡色柱超出坐标轴范围，其数值标在柱上。† 对 Caliby，Exact 在集成条件设计（每条骨架 32 个构象）下测量，Fast 在单骨架设计下测量。各模型的定义见 SI。

**图 5. Claude 的优化加速了基因组学模型与蛋白质语言模型。** 在单块 NVIDIA H100 80 GB GPU 上，各模型前向传播相对 default（虚线，1×）的加速比，计算方式为相同输入与 batch 下 default 耗时除以该模式耗时。每个模型在其饱和 batch size 或指定 batch size 下计时；各模型的输入、batch 与计时细节见 SI。Enformer 同时展示 PyTorch 与原版 TensorFlow 实现，各自与其自身的 default 比较。† Evo 2 40B 模型在两张 GPU 上运行。ESM C 与 Profluent-E1 展示其最大规模。Exact 与 default 逐比特一致；ChromBPNet 的 Fast 模式（如未修改模型一样以 TF32 执行卷积）并非逐比特一致，但仍在 default 自身的批次间波动范围内（见 SI）。

**图 6. 在汇聚的生物分子界面集合上，各优化模式与 default 在统计上无法区分。** A：各模型与模式下，排名第一的预测达到 DockQ ≥ 0.23 的 FoldBench-Lite 界面（蛋白质–蛋白质、抗体–抗原、蛋白质–多肽、蛋白质–DNA、蛋白质–RNA）占比；*n* 为靶标数。须线表示相对 default 的配对变化的 95% 百分位自助法置信区间（20,000 次靶标重采样），绘制在 default 比率加区间边界处，因此夹住该模式柱的顶端。∗ AlphaFold3（JAX 与 PyTorch）使用 OpenFold3-p2 权重。ColabFold 的第二个优化配置在此标为 Fast，它与图 3 中的 Big 是同一配置。B：具备全部四种模式的 13 个配置的汇聚结果（不含 ColabFold；1,925 个"模型–靶标"配对；面板标签把两个 ESMFold2 checkpoint 都计入了）。

**图 7. Big 模式使得在单个 GPU 节点上对多达 10,761 个 token 的生物分子系统进行准确预测成为可能。** 每个小图展示一次预测（按亚基类型着色）与实验结构（灰色）的叠合，并给出 PDB 条目与 token 数、所用模型与硬件（所述类型的 8 块 GPU）、相对实验结构的 TM-score，以及全部界面中预测为可接受（DockQ ≥ 0.23）的界面数。上三行为准确预测（TM-score ≥ 0.8、可接受界面 ≥ 70%、无冲突标记的链；评分前即固定该判据），下行为不准确的预测。

**图 8. Big 模式在单个 NVIDIA B300 节点上对约 31,000 至 70,000 个残基的组装体运行结构预测，但预测发生坍缩。** 上：对七个完整病毒衣壳与蛋白外壳的单样本预测；下：每次运行被要求复现的实验组装体（PDB 1LP3、6HTX、9C3J、9P4N、9EQW、9I8E、9BC8）。标注给出模型与残基数（对这些全蛋白系统，一个残基一个 token）；所有运行均使用单节点 8 块 B300 GPU，其中 AAV2、EV-D68 与 HBV 运行使用了各自独立的张量并行版本模型。EV-D68、带受体的 AAV9、picobirnavirus 与 encapsulin 外壳运行使用单次 trunk 传递、不做 recycling；AAV2 运行使用 10 步 recycling 与晶体结构模板。每次运行都完成了，但每次预测都发生了坍缩（三个被评分的预测 TM-score 分别为 0.14、0.08、0.08）。每个小图都缩放以填满其方框；灰色 ×*n* 表示该预测相对其下方实验组装体的放大倍数。

**图 9. 单个 Claude 模型在单块 NVIDIA H200 GPU、24 小时墙钟时间内，设计出的 de novo 结合体，其计算机模拟得分与可以使用约 100 倍 GPU 小时的早期流程相当。** 每条曲线展示在 3、6、9、12、18、24 小时时刻，某个 Claude 模型所选出设计的计算机模拟结合得分的**中位数**（上）或**最大值**（下），已在 16 个靶标上、每个靶标至多 5 次独立运行上取平均。计算机模拟结合得分为 ipSAE [31]，取值 0 到 1，衡量结构预测器对结合体与靶标之间预测界面的置信度。一个设计的得分取其复合物的两个方向 ipSAE 值中较低者（5 个种子中的最优），再对 ESMFold2、ESMFold2-Fast 与 Protenix v2 取平均；对两个 GDF-8 靶标，还要减去针对相关蛋白 GDF-11 的相应得分的一半。得分分别对应估算的 GPU 成本（左；每小时 5 美元，见方法；下方为墙钟小时数）、按 API 定价的 Claude token 成本（中）以及两者之和（右）。虚线标记早期由 Claude Mythos 5.1 完成的单靶标流程最终 30 个设计的中位数或最大值得分，这些流程可以使用子代理、每个靶标最多 10,000 美元 GPU 时间，按相同方式评分、在相同靶标上取平均（见方法）。纵轴不从零开始。

---

## 引言

深度学习模型如今能够预测蛋白质及其与其他生物分子复合物的三维结构 [1,2]，生成新的蛋白质骨架与序列 [3,4]，并从 DNA 序列中读出调控信息 [5,6]。这些模型中有许多是开源的，它们已经共同成为分子生物学家（包括药物发现与开发领域）日常使用的工具。然而，以"战役级"（campaign scale）规模运行这些模型所需的算力，超出大多数研究者能够获得的资源。例如，Claude 最近编排开源的设计与结构预测模型，自主设计蛋白结合体 [7]，单个靶标的流程在 24 小时内可能花掉高达 10,000 美元的云端 GPU 时间。按标价计算，这一预算大约相当于 2,500 NVIDIA H100 GPU 小时。

我们开始探索推理优化，让这些模型运行得更高效，从而让这类研究更容易获得。作为一项早期成果，Claude Mythos 5.1 通过定制 GPU 内核与缓存中间结果，把七个用于生物学的开源深度学习模型加速了最多 2.5 倍，且输出完全一致 [8]。本文报告一项范围大得多的工作：一个内部的通用研究模型优化了 30 多个开源模型（36 个实现），涵盖结构预测、蛋白质设计、基因组学和蛋白质语言建模（见补充材料 SI）。对每个模型，Claude 都产出一个优化包，它使用原始权重运行，并提供最多三种模式，称为 Exact、Fast 和 Big（下文详述）。

Fast 模式让结构预测模型的前向传播，比我们为每个未修改模型找到的最快正确配置平均快 **4.1 倍**（13 个模型），Exact 模式快 **1.6 倍**（14 个模型；图 3）。在汇聚的生物分子界面基准上，两种模式都没有可测的精度变化（图 6）。Claude 还开发了 FlashPairformer v1——面向三角运算（这类模型的主要开销来源）的 GPU 内核（图 2）。Big 模式使得在单个 8-GPU 节点上对超过 10,000 个 token 的分子机器进行准确预测成为可能（图 1 与图 7）。它还在 8 块 B300 GPU 上，对整个病毒衣壳以及多达 70,320 个残基的蛋白区室完成了推理，尽管这些预测并不准确（图 8）。单个 Claude 模型用一块 H200 GPU、24 小时，设计出的结合体，其计算机模拟得分与 Claude Mythos 5.1 用大约两个数量级更大的 GPU 预算所运行的单靶标流程相当（图 9）。它使用了优化后的模型，以及我们早期 agentic 设计协议的简化版本。我们公开全部优化代码。

## 结果

### FlashPairformer：更快的三角运算内核

现代结构预测模型，如 AlphaFold3 [2]、OpenFold3 [9] 和 Boltz-2 [10]，把大部分运行时间与显存花在**三角注意力**与**三角乘法**上——这两个运算继承自 AlphaFold2 [1]。二者都用 token *i* 和 *j* 各自与每个 token *k* 构成的配对表示，来更新 token 对 (*i*, *j*) 的表示。这些运算让模型能够推理生物分子系统的几何构型，但其代价随 token 数呈**三次方**增长：系统规模翻倍，其计算量大约增加八倍。降低这一代价的标准做法是编写 GPU 内核，即把许多小操作融合、调度为少量高效操作的底层程序 [11,12]。NVIDIA 已经为三角注意力与三角乘法开发了专用内核，最初在 cuEquivariance [13] 中，最近在 BioNeMo Inference Runtime（BioNeMo-IR）中 [14]。

我们与 Claude 合作开发了 FlashPairformer——我们自己针对这两种运算的内核，并将其当前版本（v1）与业界标准内核 [13] 做了基准对比（图 2）。在 AlphaFold3 与 Boltz-2 使用的 pair 宽度 128 下，FlashPairformer v1 的三角注意力比业界标准快 **2.7 倍**，三角乘法快 **1.7 倍**。在 Protenix v2 使用的 pair 宽度 256 下，二者分别快 **2.9 倍**和 **3.2 倍**。这些加速比是单块 NVIDIA H100 GPU 上、从 256 到 2,048 个 token 的七个输入尺寸上的几何平均，且 FlashPairformer v1 在**每一个尺寸**上都更快（第 S9 节）。

### Claude 加速了 30 多个生物分子模型

可跨模型迁移的内核只贡献了增益的一部分。我们还让 Claude 寻找**针对每个具体模型**的优化。典型改动包括：把原始代码会重复计算的中间结果记忆化；把总是产生相同结果的分支做常量折叠；把 GPU 操作捕获一次后作为 CUDA graph 重放，从而免去 Python 开销；把小操作链融合进单个内核；通过让张量保持在 GPU 上来避免主机与设备之间的传输；以及异步执行数据加载与输出写入，使 GPU 不因等待 CPU 而停顿。第 S1–S6 节给出每个模型的基准设置与改动。

每个优化包都用原始权重运行，并提供最多三种模式：

- **Exact** 只改变计算的**执行方式**。其输出与未修改模型的输出做了逐比特比较，其中若干模型在双方都启用确定性设置的情况下进行比较。
- **Fast** 还允许那些可能轻微改变结果的改动，例如更低精度的算术，或舍入方式不同的融合内核。针对每个模型的测试把 Fast 的输出与 default 比较；对多数模型，测试检查 Fast 的输出是否落在 default 输出的"种子间波动范围"之内。
- **Big** 降低峰值显存，使更大的输入能够放进 GPU 显存。例如，它把最大的中间张量分块处理，或在多 GPU 上把 pair 表示沿一台机器内的各 GPU 切分。Big 的检查方式与 Fast 相同。

SI 报告了每个模型的检查结果。

我们把每个优化包与一个称为 **default** 的强基线比较。default 是未修改的模型，在同一块 GPU 上，以一个有经验的用户所能找到的最快正确方式运行。例如：启用模型支持但默认未开启的可选融合内核；使用能打满 GPU 的 batch size；或让多个进程共享一块 GPU。我们把完全按作者发布状态运行的未修改模型称为 **base**；凡 default 与 base 存在差异之处，SI 都会说明差异。本报告中所有速度与显存测量都在 NVIDIA H100 80 GB GPU 上完成。

计时的对象随模型族而异，因为不同模型的用户关心不同的量。结构预测计时的是**每个复合物的前向（预测）调用**；hallucination 计时的是**每个设计步骤**；结构生成计时的是**每 GPU 秒的设计数**；反向折叠计时的是**一次设计流程的端到端墙钟时间**（含启动）；基因组学模型同时计时**前向传播**与**整个任务**；蛋白质语言模型计时的是**每个序列的耗时**（SI 第 16 页）。

图 3 展示了 14 个结构预测模型（15 个配置，把 ESMFold2 的两个 checkpoint 分开计算）的前向传播加速比，每个结果都是在所基准测试的输入尺寸（通常是 200 到 1,400 个 token 的七个尺寸）上的几何平均。Fast 模式比 default 快 **2.3 倍（OpenDDE）到 6.4 倍（Chai-1）**，平均 **4.1 倍**。Exact 模式在输出逐比特一致的前提下，平均快 **1.6 倍**；Big 模式平均快 **3.4 倍**。AlphaFold3（PyTorch）是相对 default 的 AlphaFold3（JAX）测量的，这就是为什么它的 Exact 柱低于 1×；若相对其自身的 default 配置测量，它的 Exact 模式快 2.9 倍（SI）。

这些优化还加速了多种架构的蛋白质设计模型，包括用于 hallucination 的结构预测网络 [15]、用于结构生成的扩散与流匹配模型 [3,16–20]，以及用于反向折叠的图神经网络与 Transformer [4,21]（图 4）。在四项设计质量基准中，没有任何优化模式使平均设计质量发生可测变化（SI）。对基因组学模型 [5,6,22] 与蛋白质语言模型 [23,24]，Exact 模式让前向传播比 default 快 **1.2–5.0 倍**（图 5）；SI 还覆盖了完整的基因组学任务——在这类任务中，输入准备与输出写入的耗时可能超过推理本身。

这类优化对一支有经验的工程团队来说，可能每个模型都要花上数周，而且工作往往无法在模型之间迁移。Claude 在**不到四周**内优化了上述**全部**模型，主要由 Anthropic 的两位技术成员监督，他们具备生物分子建模经验，但在推理优化或内核工程方面**没有任何经验**。

### 加速模式保持了结构预测精度

除 AF2 initial guess 外，我们对每个结构预测模型都检验了优化模式是否改变精度。我们在 **FoldBench-Lite** 上运行每种模式（该基准把 FoldBench 基准 [25] 的一部分与更近期 PDB 条目中的复合物结合起来，见方法）。对每个模型，我们取该模型依据自身置信度评分排名第一的预测，并用两种方式打分：界面（蛋白质–蛋白质、抗体–抗原、蛋白质–多肽、蛋白质–DNA、蛋白质–RNA）用 DockQ [26,27] 评分，DockQ ≥ 0.23（CAPRI 可接受质量类别的阈值）即判为可接受；单链用 lDDT [28] 评分。SI 还比较了配体构象（配体 RMSD）与模型自身的置信度评分。

**界面精度没有可测的变化。** 在 13 个模型配置（1,925 个"模型–靶标"配对，每个配置 124 至 153 个靶标）上汇聚后，可接受界面的占比为：default **54.8%**、Exact **55.0%**、Fast **54.5%**、Big **54.2%**（图 6B）。三项汇聚变化（+0.2、−0.4、−0.6 个百分点，舍入前计算）的 95% 置信区间（CI）**都没有排除零**。在 41 项与 default 的逐模型比较中，只有 ESMFold2 的 Exact 模式 95% CI 排除了零（+4.6 个百分点）。没有任何逐模型的界面成功率下降能与零区分开。不过，有六个区间**恰好终止于零**，其中包括四项最大降幅中的三项，降幅在 2.2 至 3.5 个百分点之间（OpenFold3-p2 的 Fast 与 Big、RoseTTAFold3 的 Exact 与 Big）。例如，RoseTTAFold3 Big 变化为 −3.5 个百分点，95% CI 为 −7.7 至 0.0，因此这些模式下的轻微损失不能完全排除。当以**靶标**（而非界面）为等权单位时，OpenFold3-p2 Fast 的区间（−3.4 个百分点）刚好落到零以下。

单链精度同样变化很小。这些逐模型比较的样本更小（每条链 9–13 个）。其 95% CI 仅在六项小的平均 lDDT 下降（0.003–0.040）和六项小的上升中才排除零（SI）。

Exact 与 default 在 FoldBench-Lite 上可能出现轻微差异，其原因是与优化本身无关的。该基准按每个模型的标准推理设置运行（即用户实际使用的方式），而其中若干模型在这些设置下**并非确定性**的，因此 default 自身每次运行的预测都会略有不同。已知 OpenFold3-p2、OpenBind-0 和 AlphaFold3（JAX）存在这一情况；对 default 的重跑也改变了 Protenix v1、Protenix v2 和 ColabFold 的部分预测。因此我们**另行**检查了 Exact 与 default 的逐比特一致性，在双方都启用确定性设置的条件下，在受检的 11 个配置中有 10 个在 FoldBench-Lite 输入上成立（SI）。因此，Exact 与 default 在该基准上的细微差异，最可能反映的是运行的随机波动，而非优化的影响。

### Big 模式使超大规模生物分子系统的建模成为可能

大型分子机器——如核糖体、呼吸链复合物和分子伴侣——承担了细胞中的大部分工作。许多这类机器由数十个亚基组成，只有在亚基正确组装时才发挥功能。预测这类大型系统的结构，过去通常需要跨多个 GPU 节点做推理 [29]，而这是大多数分子生物学家所不具备的计算资源。

我们让 Claude 降低建模此类系统所需的显存。由此得到的 **Big 模式**使得在**单个 8-GPU 节点**上，对超过 10,000 个 token（氨基酸、核苷酸，以及来自小分子和离子的原子）的系统进行准确预测成为可能（图 7）。在 Big 模式下被准确预测的分子机器包括人线粒体复合物 I、伴侣蛋白 TRiC、一个蛋白酶体以及一个细菌核糖体，它们各自都与其实验结构高度吻合（TM-score 在 0.92 到 0.997 之间；图 7 上三行）。在被准确预测的系统中，有六个（包括三个复合物 I 结构和 70S 核糖体）包含超过 10,000 个 token。作为对比，AlphaFold3 论文着重介绍的对人 40S 核糖体亚基的准确预测，规模为 7,663 个残基 [2]。Flagship Labs 最近报告，用一个专有的共折叠模型 LightFold，在单块 NVIDIA RTX PRO 6000 GPU 上预测了同一个大肠杆菌 70S 核糖体（PDB 4YBB）[30]。本文预测的酵母 TRiC–微管蛋白复合物（PDB 7YLW）包含 9,611 个残基（10,147 个 token，因为其 47 个结合配体与离子的每个非氢原子都算一个 token）。AlphaFold3——这些模型大多沿用的架构——最大只在 768 个 token 的裁剪片段上训练过 [2]。被准确预测的六个最大系统（均使用结构模板）是该规模的 **13 倍以上**。

并非每个系统都能被准确预测。图 7 下排展示了五个与实验结构显著偏离的预测。这五个系统中，有两个（Huc 氢化酶与 SIR2 十二聚体）曾被另一个模型准确预测。

随后我们通过让 Claude 预测与这类模型此前报告过的最大组装体规模相当甚至更大的结构，来测试 Big 模式的极限 [29]。Claude 对约 31,000 至 70,000 个残基的完整病毒衣壳与蛋白区室运行了结构预测（图 8），使用单个 8 块 B300 GPU 的节点，并且对其中的四次运行使用了缩短的协议（单次 trunk 传递，不做 recycling）。七次预测虽然都产出了结果，但**没有一次是准确的**。相反，每次预测都坍缩成一个致密、相互穿插的球状体，直径约为真实组装体的四分之一；在被评分的三次中，TM-score 为 0.08–0.14。我们推测，这种坍缩反映的是**泛化失败**，因为这些组装体的规模是 AlphaFold3 最大训练裁剪片段的 40 到 90 倍。缩短的协议可能也有影响，但 AAV2 那次运行即便使用了 recycling 与模板，仍然发生了坍缩。尽管如此，这些运行表明，这一规模的推理如今只需要**一个** GPU 节点，随着模型本身不断改进，这将降低对日益复杂的生物系统建模的门槛。

### Claude 高效设计 de novo 蛋白结合体

在我们早先的结合体设计研究中 [7]，Claude 使用一个约 16,000 词的提示词，并被鼓励使用子代理。每个单靶标流程可在 24 小时内、在 Modal 云端 GPU 上花掉最多 10,000 美元（按标价约合 2,500 H100 GPU 小时）。在本研究中，我们给单个 Claude 模型一块 NVIDIA H200 GPU 和 24 小时墙钟时间，**不给联网、不给子代理、也没有人工引导设计**。它收到的提示词约 1,100 词，简要描述了靶标，指明了靶标的结构、一个装有近期蛋白质设计文献 46 篇论文的文件夹（与早先研究基本相同），以及一份预装设计工具（包括加速后的模型）的速查表，并要求产出 **30 个设计**，使其所述的评分（权衡设计质量与多样性）尽可能高（评分公式见第 S8.3 节）。我们用三个 Claude 模型（Mythos 5.1、Mythos 5、Opus 5）各对早先研究的 16 个靶标运行了五次。在本报告中，我们**只用 ipSAE** [31] 来给设计打分，这是一种已被证明能预测实验结合情况的计算机模拟结合评分 [32]。

三个 Claude 模型在 16 个靶标上平均后的**中位得分**与**最高得分**，都接近或超过了 Mythos 5.1 在 16 个单靶标流程中最终设计的相应得分（图 9；方法）。单 GPU 运行所使用的 GPU 预算约小 **100 倍**（24 H200 GPU 小时，对比最多 10,000 美元或按标价约 2,500 H100 GPU 小时）。24 小时后，中位得分为：Opus 5 为 **0.785**、Mythos 5.1 为 **0.781**、Mythos 5 为 **0.739**，而早先流程为 **0.749**；最佳得分分别为 **0.833、0.825、0.813**，对比 **0.817**。在逐小时记录中，靶标平均的中位得分在 Opus 5 的运行中于 **12 小时**后达到早先流程的中位数（每次运行的 GPU 与 token 合计成本约 117–136 美元），Mythos 5.1 的运行于 **13 小时**后达到（每次约 157–196 美元），而 Mythos 5 的运行在 24 小时内未能达到。这些运行产出的设计尚未做实验验证。第 S8 节复现了这些运行的提示词，并报告了更多结果，包括不使用加速模型的运行。

## 讨论

在四周内，Claude 加速了 30 多个被广泛使用的生物分子模型，使其中大多数更加省显存，并对几乎所有优化模式都与 default 做了对照检查。其 FlashPairformer v1 内核针对现代结构预测器开销最大的三角运算，在三角注意力上平均比业界标准内核 [13] 快 **2.7–2.9 倍**、在三角乘法上快 **1.7–3.2 倍**（取决于 pair 宽度）。其显存优化使得在**单个 GPU 节点**上准确建模超过 10,000 个 token 的分子机器成为可能。这些优化后的模型还支撑了计算机模拟结合体设计，在约小 100 倍的 GPU 预算下达到相当的质量，且 agentic 协议更为简单。给定一个定义良好的目标和合适的工具，Claude 能够以极少的引导有效优化其设计。

本研究存在若干局限：

- 所有速度与显存结果都来自 NVIDIA H100 GPU，且限定在 SI 所述 batch size、输入尺寸与上游版本上，在别处可能不同。例如，我们的 ColabFold 加速比是相对 1.6.1 版测得的；后续 ColabFold 版本增加了自己的可选融合内核，我们没有对此做基准测试 [33,34]。
- 我们的汇总柱也是对所测输入尺寸取的平均。Exact 与 Fast 的显存占用可能高于 default（最多 3.2 倍；SI），因此当显存成为瓶颈时，应使用 Big 模式。
- 我们测量了下游精度（针对设计模型以及除 AF2 initial guess 外的所有结构预测模型）。基因组学与蛋白质语言模型只与 default 的输出做了比较，且全部吻合。另有少量对其他模型的检查**未通过**（SI）。
- 远超模型训练上下文规模的预测发生了坍缩（图 8），因此 Big 模式扩展的是**能算什么**，而不是**模型学到了什么**。
- 设计结果是计算机模拟得分，而非实验测量。
- 我们没有把优化模型的贡献与简化协议的贡献分离开来，token 成本也只是按标价估算的。

我们的结果表明，前沿 AI 模型能够帮助科学家以更高的速度和更大的便利来构建与维护他们的计算工具。我们公开优化后的代码，供社区使用。

## 方法

### 模型与优化包

36 个优化包分为六个族：共折叠与结构预测（14）、hallucination（3）、结构生成（6）、反向折叠（3）、基因组学（7）、蛋白质语言模型（3）。

AlphaFold3 在两个社区实现上做了优化：Google DeepMind 推理代码的一个 JAX 分支，以及 xfold——一个独立的 PyTorch 重实现；两者都使用 **OpenFold3-p2 权重**运行，而非 AlphaFold3 自身的参数。OpenFold3 使用其 preview-2 权重（OpenFold3-p2）以及 OpenBind-0 权重运行 [9]。Enformer 在两个实现上做了优化（SI）。

结构预测模型为：ESMFold2 [35]、OpenFold3 [9]、AlphaFold3 [2,36,37]、Protenix v1 与 v2 [38–40]、OpenDDE [41]、Boltz-2 [10]、Chai-1 [42]、RoseTTAFold3 [43]、AtlasFold [44]、ColabFold [45]、AF2 initial guess [46]。

设计模型为：ColabDesign/BindCraft [15,47]、ESMFold2-inverse hallucination（EF2-inv，构建于 ESMFold2 之上 [35]）、Mosaic [48]、Genie 3 [18]、PXDesign [19]、Proteina-Complexa [20]、RFdiffusion3 [16]、RFdiffusion [3]、BoltzGen [17]、ProteinMPNN [4]、Caliby [49]、ESM-IF1 [21]。

基因组学模型为：Evo 2 [22]、Borzoi [6]、Flashzoi [50]、ChromBPNet [51]、Enformer [5]、GPN-Star [52]。

蛋白质语言模型为：ESM C [24]、Profluent-E1 [53]、ProGen2 [23]。

### 基准测试协议

**default** 是未修改的模型在同一块 GPU 上、我们所能找到的最快正确配置（SI 第 16 页）。加速比是相同输入尺寸与设置下 default 耗时与该模式耗时之比，batch size 由 SI 中各模型的规则设定。每个模式都与 default 在同一批运行中计时。时间不含预热，但反向折叠口径与基因组学整任务口径除外——它们是端到端墙钟时间，包含模型加载等一次性开销。峰值显存为 NVIDIA 管理库报告的设备级峰值（相对空闲态），除非 SI 另有说明。Exact 模式在模型专用输入集上检查逐比特一致输出（对若干模型，在确定性设置下），并排除 default 自身都无法复现的输出。Fast 与 Big 模式通过模型专用测试检查：落在 default 的种子间波动范围内（结构预测与反向折叠）、对设计优化做逐步比较（hallucination），或逐比特比较（部分结构生成与基因组学包）。

本节数值为舍入后的结果；SI 中的加速比对数值做**截断**处理（SI 第 16 页）。

### 结构预测精度

FoldBench-Lite 汇集了 FoldBench [25] 中的复合物、2025 年 7 月以来发布的 PDB 条目中的复合物（选取规则改编自 FoldBench，以覆盖序列与尺寸的多样性），以及额外的抗体–抗原复合物。在其 249 个靶标中，图 6 使用其中 171 个（273 个界面：132 个抗体–抗原、61 个蛋白质–蛋白质、31 个蛋白质–DNA、27 个蛋白质–RNA、22 个蛋白质–多肽）——这些靶标至少有一个此类界面在 default 与全部优化模式下都被至少一个模型评分过；其余 78 个中，有 76 个是蛋白质–配体复合物，或蛋白质、DNA、RNA 单体，SI 按模型分别报告。

每个模型展示在其 default 与全部模式都被评分的靶标上（AtlasFold 与 ColabFold 只能建模蛋白质，故只在移除了配体、离子和残基修饰的纯蛋白靶标上展示）。每个模型与模式对每个靶标都使用相同随机种子，top-1 预测是在四种模式共有种子上按模型自身指标排名最优的样本（并列时：种子序号更小者优先，其次样本索引更小者）。界面用 DockQ [26,27] 评分，DockQ ≥ 0.23 判为可接受。置信区间来自每个模型内部对靶标的配对百分位自助法（20,000 次重采样，模式与 default 共用），汇聚区间则跨模型合并。

### 大型系统

图 7 中的 20 个预测（18 个组装体、17 个 PDB 条目）是从我们对 20 个组装体（19 个 PDB 条目）、规模 4,000 至 11,000 个 token 所做的最多各四个模型共 190 次预测中，经评分后筛选出来的。它们在 Big 模式下、在单个由 8 块 H100、H200 或 B200 GPU 组成的节点上运行，pair 表示按行切分到八块 GPU 上（该设置在 SI 中未做基准测试），并对照已存入的结构用 TM-score [54] 与逐界面 DockQ 评分。小图展示每次运行五个样本中置信度最高的一个（对 70S 核糖体与 GroEL，展示 TM-score 最高的样本）；未通过精度判据的运行会换新种子重跑并展示最好的种子，四个准确小图（PDB 8IO9、7YLW、7X0V 与 GroEL）即属此类。除 ESMFold2、20S 蛋白酶体与 SIR2 十二聚体的预测外，所有预测都使用了 2021 年 9 月 30 日（AlphaFold3 的训练截止日期）之前发布的模板（对更早的条目，为发布日期前 60 天）；有五个被准确预测的条目（PDB 1J5E、1PMA、1SS8、4YBB 与 8RUC）早于该日期，可能存在于模型的训练数据中。

### 结合体设计

每次运行（见"结果"）返回 30 个设计，用 ESMFold2、ESMFold2-Fast 与 Protenix v2 折叠（各五个种子），并按图 9 图注所述用 ipSAE-min [31] 评分。在每个检查点，每次运行的中位数与最佳得分先在运行间取平均，再在靶标间取平均。参考线来自 Claude Mythos 5.1 在 2026 年 8 月 8–11 日运行的 16 个单靶标流程，其最终 30 个设计按相同方式重新评分。每个这样的流程都可以使用子代理和最多 10,000 美元的 GPU 时间，我们早先研究 [7] 的流程也是如此（由 Claude Mythos Preview 运行，其中三个靶标还用了 Claude Opus 4.8）。每小时 5 美元的 GPU 费率是 Modal 上单块 H200 GPU 的标价（每小时 4.54 美元）加上主机 CPU 与内存的额度（例如四个物理核心与 32 GiB 为每小时 0.44 美元）后向上取整的结果 [55]。token 成本是按 API 标价（对 Mythos 5.1，即 Claude Fable 5.1 的价格）对设计模型自身 token 的估算，5 分钟与 1 小时两种提示缓存写入费率都计入。在逐小时记录中，参考中位数（0.7491）最早由 Opus 5 在第 12 小时达到（0.7494），由 Mythos 5.1 在第 13 小时达到（0.7507）。

## 数据与代码可用性

全部模型的优化代码见：<https://github.com/anthropics/uplifting-biomolecular-modeling>

## 致谢

我们感谢本工作中所优化开源模型的开发者公开其代码与权重，感谢 NVIDIA 提供 cuEquivariance 与 BioNeMo-IR 软件。我们感谢 Aleksei Lorenz 对 Claude Science 的改进；Xander Balwit 的编辑审阅；Nathan Frey 的讨论与审阅；以及 Martin Pacesa（苏黎世大学）、Mohammed AlQuraishi 与 Christina Floristean（哥伦比亚大学）、Roshan Rao 与 Zeming Lin（Biohub）的讨论及在发布前对本工作部分内容的审阅。

## 利益冲突

C.S.、R.S.、R.B.、V.F. 与 A.S. 隶属于 Anthropic。

## AI 工具的使用

Claude——由 Anthropic 开发的 AI 模型——在人类监督下完成了本文所述的优化工作与数据分析，并起草了本报告。

---

## 参考文献

> 保留原文格式，未做翻译。

 [1] John Jumper et al. Highly accurate protein structure prediction with AlphaFold. *Nature*, 596:583–589, 2021. doi:10.1038/s41586-021-03819-2.
 [2] Josh Abramson et al. Accurate structure prediction of biomolecular interactions with AlphaFold 3. *Nature*, 630:493–500, 2024. doi:10.1038/s41586-024-07487-w.
 [3] Joseph L. Watson et al. De novo design of protein structure and function with RFdiffusion. *Nature*, 620:1089–1100, 2023. doi:10.1038/s41586-023-06415-8.
 [4] Justas Dauparas et al. Robust deep learning-based protein sequence design using ProteinMPNN. *Science*, 378:49–56, 2022. doi:10.1126/science.add2187.
 [5] Žiga Avsec et al. Effective gene expression prediction from sequence by integrating long-range interactions. *Nature Methods*, 18:1196–1203, 2021. doi:10.1038/s41592-021-01252-x.
 [6] Johannes Linder, Divyanshi Srivastava, Han Yuan, Vikram Agarwal, and David R. Kelley. Predicting RNA-seq coverage from DNA sequence as a unifying model of gene regulation. *Nature Genetics*, 57:949–961, 2025. doi:10.1038/s41588-024-02053-6.
 [7] Claude Science and Amir Shanehsazzadeh. Autonomous de novo protein binder design with Claude. Technical report, Anthropic, August 2026. https://www-cdn.anthropic.com/30bf50e22a01388bb29bf077ee3f244531594b7a.pdf. Data and prompts: https://huggingface.co/datasets/Anthropic/claude-protein-binder-design.
 [8] Anthropic. Introducing Claude Fable 5.1 and Claude Mythos 5.1. https://www.anthropic.com/claude-fable-and-mythos-5-1, 2026.
 [9] The OpenFold3 Team. OpenFold3-preview: open-source biomolecular structure prediction (software; openfold3 releases 0.4.1 and 0.5.0; the 0.5.0 default checkpoint is OpenBind-0). https://github.com/aqlaboratory/openfold-3, 2026. Release 0.4.1, doi:10.5281/zenodo.19485145; release 0.5.0, doi:10.5281/zenodo.22042719.
[10] Saro Passaro et al. Boltz-2: Towards accurate and efficient binding affinity prediction. *bioRxiv*, 2025. doi:10.1101/2025.06.14.659707.
[11] Tri Dao, Daniel Y. Fu, Stefano Ermon, Atri Rudra, and Christopher Ré. FlashAttention: Fast and memory-efficient exact attention with IO-awareness. In *Advances in Neural Information Processing Systems (NeurIPS)*, 2022. arXiv:2205.14135.
[12] Philippe Tillet, H. T. Kung, and David Cox. Triton: an intermediate language and compiler for tiled neural network computations. In *Proceedings of the 3rd ACM SIGPLAN International Workshop on Machine Learning and Programming Languages*, pages 10–19, 2019. doi:10.1145/3315508.3329973.
[13] NVIDIA. cuEquivariance: a library of low-level primitives and tensor operations to accelerate equivariant neural networks. https://github.com/NVIDIA/cuEquivariance, 2026.
[14] NVIDIA. BioNeMo inference runtime: easy, fast, and memory-efficient structure prediction inference. https://github.com/NVIDIA-BioNeMo/BioNeMo-Inference-Runtime; documentation: https://docs.nvidia.com/bionemo/inference-runtime/overview/, 2026.
[15] Martin Pacesa et al. One-shot design of functional protein binders with BindCraft. *Nature*, 646:483–492, 2025. doi:10.1038/s41586-025-09429-6.
[16] Jasper Butcher et al. De novo design of all-atom biomolecular interactions with RFdiffusion3. *bioRxiv*, 2025. doi:10.1101/2025.09.18.676967.
[17] Hannes Stark et al. BoltzGen: Toward universal binder design. *bioRxiv*, 2025. doi:10.1101/2025.11.20.689494.
[18] Yeqing Lin et al. Fast and ultra-capable protein design: advancing the frontier through atomistic SE(3)-equivariance with Genie 3. *bioRxiv*, 2026. doi:10.64898/2026.05.01.722168.
[19] Protenix Team et al. PXDesign: Fast, modular, and accurate de novo design of protein binders. *bioRxiv*, 2025. doi:10.1101/2025.08.15.670450.
[20] Kieran Didi et al. Scaling atomistic protein binder design with generative pretraining and test-time compute. arXiv:2603.27950, 2026. doi:10.48550/arXiv.2603.27950.
[21] Chloe Hsu et al. Learning inverse folding from millions of predicted structures. In *Proceedings of the 39th International Conference on Machine Learning (ICML)*, 2022. bioRxiv doi:10.1101/2022.04.10.487779.
[22] Garyk Brixi et al. Genome modeling and design across all domains of life with Evo 2. *bioRxiv*, 2025. doi:10.1101/2025.02.18.638918.
[23] Erik Nijkamp, Jeffrey A. Ruffolo, Eli N. Weinstein, Nikhil Naik, and Ali Madani. ProGen2: Exploring the boundaries of protein language models. *Cell Systems*, 14:968–978, 2023. doi:10.1016/j.cels.2023.10.002.
[24] ESM Team. ESM Cambrian: Revealing the mysteries of proteins with unsupervised learning. EvolutionaryScale website, https://www.evolutionaryscale.ai/blog/esm-cambrian, 2024.
[25] Sheng Xu et al. Benchmarking all-atom biomolecular structure prediction with FoldBench. *Nature Communications*, 17:442, 2025. doi:10.1038/s41467-025-67127-3.
[26] Sankar Basu and Björn Wallner. DockQ: A quality measure for protein-protein docking models. *PLOS ONE*, 11:e0161879, 2016. doi:10.1371/journal.pone.0161879.
[27] Claudio Mirabello and Björn Wallner. DockQ v2: improved automatic quality measure for protein multimers, nucleic acids, and small molecules. *Bioinformatics*, 40:btae586, 2024. doi:10.1093/bioinformatics/btae586.
[28] Valerio Mariani, Marco Biasini, Alessandro Barbato, and Torsten Schwede. lDDT: a local superposition-free score for comparing protein structures and models using distance difference tests. *Bioinformatics*, 29(21):2722–2728, 2013. doi:10.1093/bioinformatics/btt473.
[29] Dejun Lin, Simon Chu, Vishanth Iyer, et al. Fold-CP: A context parallelism framework for biomolecular modeling. arXiv:2603.14806, 2026. doi:10.48550/arXiv.2603.14806.
[30] Flagship Labs. Structure prediction at the scale of biology. *The Labs Report (Flagship Labs)*, https://flagshiplabs.substack.com/p/structure-prediction-at-the-scale, August 2026.
[31] Roland L. Dunbrack. Rēs ipSAE loquuntur: What's wrong with AlphaFold's ipTM score and how to fix it. *bioRxiv*, 2025. doi:10.1101/2025.02.10.637595.
[32] Max D. Overath et al. Predicting experimental success in de novo binder design: A meta-analysis of 3,766 experimentally characterised binders. *bioRxiv*, 2025. doi:10.1101/2025.08.14.670059.
[33] ColabFold developers. ColabFold 1.6.2 release notes (published july 14, 2026). https://github.com/sokrypton/ColabFold/releases/tag/v1.6.2, 2026.
[34] ColabFold developers. ColabFold 1.6.3 release notes (published september 14, 2026). https://github.com/sokrypton/ColabFold/releases/tag/v1.6.3, 2026.
[35] Salvatore Candido et al. Language modeling materializes a world model of protein biology. *bioRxiv*, 2026. doi:10.64898/2026.06.03.729735.
[36] sokrypton. alphafold3: a fork of the AlphaFold 3 inference pipeline that can load OpenFold3 weights (release v3.1.4). https://github.com/sokrypton/alphafold3, 2026.
[37] Shenggan. xfold: Democratizing AlphaFold3, a PyTorch reimplementation to accelerate protein structure prediction. https://github.com/Shenggan/xfold, 2026.
[38] ByteDance AML AI4Science Team et al. Protenix – advancing structure prediction through a comprehensive AlphaFold3 reproduction. *bioRxiv*, 2025. doi:10.1101/2025.01.08.631967.
[39] Yuxuan Zhang et al. Protenix-v1: Toward high-accuracy open-source biomolecular structure prediction. *bioRxiv*, 2026. doi:10.64898/2026.02.05.703733.
[40] Yuxuan Zhang et al. Protenix-v2: Broadening the reach of structure prediction and biomolecular design. *bioRxiv*, 2026. doi:10.64898/2026.04.10.717613.
[41] Aureka AI OpenDDE Project. Folding, reasoning, and scaling with open-source drug discovery engine. arXiv:2607.03787, 2026. doi:10.48550/arXiv.2607.03787.
[42] Chai Discovery et al. Chai-1: Decoding the molecular interactions of life. *bioRxiv*, 2024. doi:10.1101/2024.10.10.615955.
[43] Nathaniel Corley et al. Accelerating biomolecular modeling with AtomWorks and RF3. *bioRxiv*, 2025. doi:10.1101/2025.08.14.670328.
[44] Seonghwan Seo. AtlasFold: trainable protein folding and co-folding models with a protein language model (release 1.0.0). https://github.com/SeonghwanSeo/atlasfold, 2026.
[45] Milot Mirdita et al. ColabFold: making protein folding accessible to all. *Nature Methods*, 19:679–682, 2022. doi:10.1038/s41592-022-01488-1.
[46] Nathaniel R. Bennett et al. Improving de novo protein binder design with deep learning. *Nature Communications*, 14:2625, 2023. doi:10.1038/s41467-023-38328-5.
[47] sokrypton. ColabDesign: making protein design accessible to all via Google Colab. https://github.com/sokrypton/ColabDesign, 2026.
[48] Escalante Bio. mosaic: composite-objective protein design. https://github.com/escalante-bio/mosaic, 2025.
[49] Richard W. Shuai, Tianyu Lu, Subhang Bhatti, Petr Kouba, and Po-Ssu Huang. Ensemble-conditioned protein sequence design with Caliby. *bioRxiv*, 2025. doi:10.1101/2025.09.30.679633.
[50] Johannes C. Hingerl, Alexander Karollus, and Julien Gagneur. Flashzoi: an enhanced Borzoi for accelerated genomic analysis. *Bioinformatics*, 41:btaf467, 2025. doi:10.1093/bioinformatics/btaf467.
[51] Anusri Pampari et al. ChromBPNet: bias factorized, base-resolution deep learning models of chromatin accessibility reveal cis-regulatory sequence syntax, transcription factor footprints and regulatory variants. *bioRxiv*, 2024. doi:10.1101/2024.12.25.630221.
[52] Chengzhong Ye et al. Predicting genome-wide functional constraints with GPN-Star. *Nature*, 2026. doi:10.1038/s41586-026-11005-5.
[53] Sarthak Jain et al. E1: Retrieval-augmented protein encoder models. *bioRxiv*, 2025. doi:10.1101/2025.11.12.688125.
[54] Yang Zhang and Jeffrey Skolnick. Scoring function for automated assessment of protein structure template quality. *Proteins: Structure, Function, and Bioinformatics*, 57:702–710, 2004. doi:10.1002/prot.20264.
[55] Modal Labs. Modal pricing. https://modal.com/pricing, 2026. Accessed September 16, 2026.
[56] Biohub. ESMFold2 and ESMFold2-Fast: model weights and the esm inference package. https://huggingface.co/biohub/ESMFold2, 2026.
[57] Biohub. esm: inference code for ESM C, ESMFold2 and related protein models. https://github.com/Biohub/esm, 2026.
[58] OpenBind. OpenBind-0: advancing open molecular structure prediction. https://openbind.uk/news/blog-openbind-0-advancing-open-molecular-structure-prediction/, 2026.
[59] Richard Evans et al. Protein complex prediction with AlphaFold-Multimer. *bioRxiv*, 2021. doi:10.1101/2021.10.04.463034.
[60] Lorenzo Tarricone, Helen E. Eisenach, Aiko Muraishi, and Charlotte M. Deane. Design-CP: Context parallelism for design of protein nanoparticles. arXiv:2607.05439, 2026. doi:10.48550/arXiv.2607.05439.
[61] Meta Fundamental AI Research. Evolutionary scale modeling (esm), fair-esm 2.0.1. https://github.com/facebookresearch/esm, 2022.
[62] Johannes C. Hingerl. borzoi-pytorch: PyTorch implementation of Borzoi and Flashzoi. https://github.com/johahi/borzoi-pytorch, 2025.
[63] Phil Wang. enformer-pytorch: implementation of Enformer in PyTorch. https://github.com/lucidrains/enformer-pytorch, 2021.
[64] Google DeepMind. deepmind-research repository, enformer directory. https://github.com/google-deepmind/deepmind-research, 2021.
[65] Casper A. Goverde, Martin Pacesa, Nicolas Goldbach, et al. Computational design of soluble and functional membrane protein analogues. *Nature*, 631:449–458, 2024. doi:10.1038/s41586-024-07601-y.

---

## 补充材料（Supplementary Information）

### SI 目录

| 章节 | 内容 | 页码 |
| --- | --- | --- |
| **S1** | 共折叠与结构预测 | 19 |
| S1.1 | ESMFold2 | 19 |
| S1.2 | ESMFold2-Fast | 23 |
| S1.3 | OpenFold3-p2 | 27 |
| S1.4 | OpenBind-0 | 31 |
| S1.5 | AlphaFold3 (JAX) | 35 |
| S1.6 | AlphaFold3 (PyTorch) | 39 |
| S1.7 | Protenix v2 | 43 |
| S1.8 | Protenix v1 | 47 |
| S1.9 | OpenDDE | 51 |
| S1.10 | Boltz-2 | 55 |
| S1.11 | Chai-1 | 59 |
| S1.12 | RoseTTAFold3 | 63 |
| S1.13 | AtlasFold | 67 |
| S1.14 | ColabFold (AF2-Multimer) | 71 |
| S1.15 | AF2 initial guess | 75 |
| **S2** | Hallucination | 77 |
| S2.1 | ColabDesign | 77 |
| S2.2 | ESMFold2-inverse hallucination | 79 |
| S2.3 | Mosaic | 81 |
| **S3** | 结构生成 | 83 |
| S3.1 | Genie 3 | 83 |
| S3.2 | PXDesign | 85 |
| S3.3 | Proteina-Complexa | 87 |
| S3.4 | RFdiffusion3 | 89 |
| S3.5 | RFdiffusion | 91 |
| S3.6 | BoltzGen | 93 |
| **S4** | 反向折叠 | 95 |
| S4.1 | ProteinMPNN | 95 |
| S4.2 | Caliby | 97 |
| S4.3 | ESM-IF1 | 99 |
| **S5** | 基因组学模型 | 101 |
| S5.1 | Evo 2 | 101 |
| S5.2 | Borzoi | 104 |
| S5.3 | Flashzoi | 106 |
| S5.4 | ChromBPNet | 108 |
| S5.5 | Enformer | 110 |
| S5.6 | Enformer (original) | 112 |
| S5.7 | GPN-Star | 114 |
| **S6** | 蛋白质语言模型 | 116 |
| S6.1 | ESM C | 116 |
| S6.2 | Profluent-E1 | 118 |
| S6.3 | ProGen2-xlarge | 121 |
| **S7** | 优化模式下的设计质量 | 123 |
| S7.1 | BinderBench：结合体设计 | 123 |
| S7.2 | UnconditionalBench：无条件骨架生成 | 124 |
| S7.3 | SCBench 与 NSRBench：反向折叠 | 125 |
| **S8** | 结合体设计：提示词与更多结果 | 127 |
| S8.1 | 提示词 | 127 |
| S8.2 | 更多结果 | 127 |
| S8.3 | 提示词文本 | 130 |
| **S9** | 内核基准 | 140 |

### SI 总体方法论

对每个被优化的模型，我们报告基准设置、基线、Claude 所做的改动以及结果。各节按模型族分组：共折叠与结构预测（S1）、hallucination（S2）、结构生成（S3）、反向折叠（S4）、基因组学模型（S5）、蛋白质语言模型（S6）。每个小节以对该模型的简要介绍开头，随后是基准设置、采样设置、可用模式、主要改动（大致按估计贡献排序）、结果以及图表。采样设置（recycling 次数、采样步数、每次调用的样本数或设计数、模型权重与输入）对 default 和每种模式都相同；凡我们的设置与上游默认不同之处，都会给出上游默认值。对结构预测模型，recycling 被设定为至少产生 11 次 trunk 传递，扩散采样设定为每次调用至少 5 个样本，在上游取值本已更高时保留上游值；采样步数保持上游默认。对多数结构预测模型，其后跟随 FoldBench-Lite 精度结果。图表展示核心测量值，在数据点几乎重合处只标一个数值。伴随口径、与 base 的比较，以及少量其他结果（例如 Evo 2 的多序列评分加速）只出现在正文中；除内核基准（表 S1）外，图中数值不在表格中重复。

**default 与 base。** 每个优化包都与一个基线比较，我们在本文中称其为 **default**，在代码实现中称其为 **stock**：未修改的上游模型在同一块 GPU 上、以我们所能找到的最快正确方式运行。default 可能与完全按作者发布状态运行该包不同——后者在本文中称 **base**，在代码实现中称 default。典型差异是：启用该包支持但自身未开启的可选融合内核；使用能打满 GPU 的 batch size；让一个进程持续存活以处理许多输入；或通过 NVIDIA Multi-Process Service（MPS）让多个进程共享一块 GPU。每个小节只说明 default 相对 base 增加了什么。我们选择与 default（而非 base）比较，是为了让报告的加速比反映**我们的改动**，而不是一个内行用户在没有我们代码的情况下也能做出的配置选择。凡 base 与 default 之间本身就存在较大差异之处，小节中会说明。

**软件版本与随机种子。** 多数小节会给出所基准测试的软件包版本与 checkpoint。对每个模型，精确的上游提交以及周边软件栈（PyTorch、JAX、Triton、cuEquivariance 或 TensorFlow 构建版本）——内核可用性、逐比特一致性与速度都依赖于它们——都列在该模型的 `STOCK.md` 和发布仓库的 `environment/requirements.lock` 中（见"数据与代码可用性"）。对上游固定框架无法在 H100 上运行的两个模型（ProGen2-xlarge 与 Enformer (original)），小节给出了实际使用的版本。每个随机模型都以固定随机种子运行，且在上游接受种子的地方，default 与每种模式使用完全相同的种子——这正是 Exact 的一致性检查与各模式配对比较得以成立的前提；纯前向模型是确定性的。

**模式。** Exact 只改变计算的执行方式，其设计目标是产出与 default 逐比特一致的输出；这一点对每个模型都做了检查，少数未通过的检查会在相应小节中说明。有若干模型在其生产设置下无法逐次运行完全复现；对这些模型，一致性是在启用该模型的确定性选项后检查的，而 default 自身也不可复现的情形则被排除在外。Fast 额外允许那些可能轻微改变输出的改动，例如更低精度的算术或舍入方式不同的融合内核；对 Fast 与 default 的比较采用模型专用测试：对多数模型，检查输出是否落在 default 自身跨随机种子或重复运行输出的波动范围内（对 AF2 initial guess，是落在固定容差内）；对 hallucination，做设计优化的逐步比较；对 Evo 2，做逐比特比较。对 PXDesign 之外的结构生成模型，以及 BoltzGen Big，未报告带接受阈值的测试；第 S7 节将其设计结果与 default 比较。每个小节都会说明检查了什么。Big 是面向更大输入的省显存模式，对峰值显存与单卡可达规模的影响取决于模型；"Big on two GPUs"（图中标为 Big ×2）把计算分散到两块 GPU 上。并非每个模型都有全部模式。

**计时口径。** 所有速度与显存测量都在 NVIDIA H100 80 GB GPU 上完成。计时对象（我们称为 clock，即"口径"）取决于模型族，因为不同模型的用户关心不同的事：

- **共折叠与结构预测**：稳态下每个复合物在模型前向（预测）调用内花费的时间，不含预热与编译；该调用包含哪些内容（例如输入特征化或输出写入）因模型而异，并在各小节中说明。输入尺寸范围为 200 到 1,400 个 token；对 ESMFold2，加速比只统计到 1,000 个 token，再往上 default 会显存不足。共折叠与 hallucination 基准的全部输入、以及 RFdiffusion 结合体设计基准的输入都是蛋白质：速度与显存基准中是蛋白质复合物，可达性测试中则是更大的蛋白组装体。由于这些输入不含配体，而共折叠模型为每个残基分配一个 token，因此正文中给出的 token 输入尺寸等于图中 "protein length (residues)" 轴上各链残基总数。可达性（reach）图展示在一块或两块 GPU 上完成的最大输入；在每次搜索中，下一个测试尺寸（大 1,000 token）都未能完成。可达性搜索检验的是输入能否放得下，而不是运行多快；对多数共折叠模型，其中部分或全部使用了比速度基准更少的 recycling 步数、trunk 传递次数或扩散步数的缩短运行，其中若干次与 3,000 或 4,000 token 下的全设置运行做了对照。因此，可达性数值是在所述 GPU 数量下的显存上限，除非搜索因输入尺寸上限而终止：Chai-1 最多接受 2,048 个 token，未修改的 Protenix v2 拒绝超过 2,560 token 的输入。对 Protenix v1 与 Protenix v2，4,000 token 下的缩短运行相较全设置运行分别显示低 16% 与 6% 的峰值显存；在 Protenix v1 的两卡搜索中，下一个尺寸因通信超时而失败（计作显存不足），因此这两个双卡数值是最不确定的；对 AlphaFold3 (PyTorch)、OpenDDE 与 RoseTTAFold3 的双卡情形，报告的可达规模可能相差尺寸阶梯的一级。
- **Hallucination**：稳态下每个设计步骤的耗时（一次设计损失的前向与反向传播，含结构预测器之外的所有评分模型）。
- **结构生成**：稳态采样跨度内每 GPU 秒的设计数，并以整趟耗时作为伴随指标。
- **反向折叠**：一次设计流程的端到端墙钟时间，从进程启动到最后一次输出写入，含模型加载等一次性开销；同时报告排除这些开销的稳态采样口径。
- **基因组学模型**：每个模型每次调用的前向传播。对在端到端任务上基准测试的三个模型（Borzoi、Flashzoi、ChromBPNet），还展示整个任务的墙钟时间（含读取与准备输入、写入输出），因为模型之外的工作可能主导任务耗时；对 Evo 2，生成在整个生成调用上计时并按 token 报告。Enformer、Enformer (original) 与 GPN-Star 只在前向调用上做了基准测试。
- **蛋白质语言模型**：前向调用内每个序列的耗时。ESM C 与 Profluent-E1 在 batch size 为 1 以及饱和 batch（default 在该 GPU 上能完成的最大 batch）下计时，default 与优化模式在同一 batch 下运行；饱和 batch 对吞吐量是更公平的比较，也是核心条件。Profluent-E1 还在检索增强输入下计时。ProGen2 在其发布的两个任务上计时：评分（每次调用一个序列）与生成（每次调用 80 个样本），其中整个生成调用计时。

加速比是其余设置完全相同情况下 default 耗时与模式耗时之比（或模式吞吐量与 default 吞吐量之比），除非小节中说明了不同的 batch size 或进程数。图中 1× 处的虚线标记 default。

**显存。** 除非小节另有说明，峰值显存为 NVIDIA 管理库（NVML）报告的设备级峰值（相对空闲态），在专门的运行中测量。部分 JAX 与 TensorFlow 模型在运行时以大的块预留 GPU 显存；对这些模型，接近或高于 1× 的设备级比值可能反映预留块的大小，而非实际使用的显存，相应小节会注明。

**结构预测精度。** 除未纳入该基准的 AF2 initial guess 外，每个结构预测小节都以 FoldBench-Lite 上的两张图结尾——该基准即正文图 5（正文方法中有描述）。第一张报告精度：对每个界面类别，排名第一的预测可接受的界面占比，即可接受指 DockQ ≥ 0.23 [26,27]（对配体则是配体均方根偏差 RMSD < 2 Å；与 FoldBench 不同，我们不再额外要求蛋白质–配体相互作用 lDDT（LDDT-PLI）高于 0.8）；对蛋白质、DNA、RNA 单体，则是排名第一预测的平均 lDDT [28]。排名第一的预测是在 default 与所有模式共有的种子上，模型自身排序分数最优的样本；每个模型只在 default 与其所有模式都评分过的界面、配体和链上展示。区间为对靶标做 10,000 次重采样得到的 95% 百分位自助法区间（正文图 5 的区间是单独计算的，末位数字可能不同）；每种模式相对 default 的配对变化在同一批重采样上计算。当某模式在某个类别上的全部变化都朝同一方向时，其配对变化的区间可能恰好终止于零；这种区间计为包含零，尽管该方向上存在小幅变化仍不能排除。在变化靶标很少时这些区间是近似的；在区间并非"单点零"的 270 项配对比较中，有 15 项排除了零，这与随机预期的数量（13.5）大致相当。

第二张图逐点比较每种模式与 default：左侧是每个界面、配体和链的排名第一预测精度（DockQ、配体 RMSD、lDDT，单位同第一张图）；右侧是模型自身的置信度分数（ipTM 与 pTM，即界面与整体预测 TM-score，以及平均 pLDDT，即预测的局部距离差检验），按靶标逐一对比。平均 pLDDT 来自各模型报告的分数；对不报告该分数的模型（AlphaFold3 (JAX)、AlphaFold3 (PyTorch)、Chai-1、ESMFold2 与 RoseTTAFold3），则来自预测结构文件，取聚合物链各残基平均逐原子 pLDDT 的均值。

与正文一样，该基准让每个模型都在其生产设置下运行。在这些设置下，已知 default 在 OpenFold3-p2、OpenBind-0 与 AlphaFold3 (JAX) 上不可逐次重现；对 default 的重跑改变了 Protenix v1、Protenix v2 与 ColabFold 的部分预测；而已知 default 在 AlphaFold3 (PyTorch) 上是可重现的。因此，在确定性设置下与 default 逐比特一致的 Exact，在这里可能与 default 不同，正如 Boltz-2 在至少 25 个靶标中各有 1 个种子上出现的情况，而报告的精度没有变化。对八个模型（OpenFold3-p2、OpenBind-0、Protenix v2、Protenix v1、OpenDDE、Boltz-2、RoseTTAFold3 与 AtlasFold），Exact 的预测来自该基准的一次重复 Exact 运行（对四个 OpenFold3-p2 靶标，来自首次运行），与正文图 5 的做法一致。

**舍入。** 在本补充材料中，加速比及其他速度比值都做**截断**而非四舍五入（测得 3.269 的加速比会印作 3.26× 或 3.2×），以使印出的加速比从不夸大测量值；峰值显存比值则四舍五入到最近值，因为截断会夸大节省量。正文与图表遵循同一规则。其他所有量（如精度与置信度分数）均四舍五入到最近值。正文对所有数值都四舍五入到最近值，因此正文中的加速比可能比此处对应值在末位大 1。

**范围。** 数值按模型、按输入尺寸报告（这一系列输入尺寸有时称为"阶梯"）；本补充材料不在模型之间取平均，小节中的几何平均是在某模式所绘制的尺寸上计算后再截断。本补充材料的全部速度、显存与可达性图表都由单一绘图包直接从基准表格生成，精度与置信度图表则直接从 FoldBench-Lite 评分记录生成。

> **关于 S1–S9 各模型小节**：SI 中约 120 页的逐模型技术细节（每个模型的基准设置、采样设置、Claude 的具体改动、结果与图表）未在本文档中逐条译出。如需，可另行逐节翻译。
