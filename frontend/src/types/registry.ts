/**
 * 注册中心与语义映射(序号 34 的契约第 3、4 条)。
 *
 * 序号 34「高质量数据集制备」已确认独立立项(架构风险 R1)。这些类型描述的
 * 不是那个平台,而是<b>它与本平台之间的接口</b>:数据集与模型在这里注册,
 * 内容存对象存储,字段到医学概念的映射写在这里。
 */

export type RegistryKind = 'DATASET' | 'MODEL' | 'ONTOLOGY'

export interface RegistryArtifact {
  id: string
  kind: RegistryKind
  name: string
  description: string | null
  /** 0 表示"注册了但还没有内容"——它与"有一版"是两回事 */
  latestVersion: number
  /** 产出它的执行记录。顺着它能追到当时的物理计划与定义版本 */
  producedByExecutionId?: string | null
  createdAt: string
  createdBy: string | null
  updatedAt: string
}

/** 已发布的版本不可变 —— 所以这个类型上没有对应的 update 请求 */
export interface RegistryVersion {
  id: string
  artifactId: string
  version: number
  /** 只有地址,没有内容:影像与模型权重不经过本平台 */
  contentUri: string
  sizeBytes: number | null
  checksumSha256: string | null
  itemCount: number | null
  producedByExecutionId?: string | null
  /** 这一版是从哪一版做出来的 —— 数据飞轮的一条边 */
  derivedFromVersionId?: string | null
  metadataJson?: string | null
  createdAt: string
  createdBy: string | null
}

export interface RegistryEdge {
  id: string
  fromType: string
  fromId: string
  relation: string
  toType: string
  /** 概念用本体 IRI 标识 —— 它的定义归 Intelligence */
  toId: string
  toLabel?: string | null
  confidence: number
  /** MANUAL / AUTO。自动抽取的要能单独复核 */
  origin: string
  createdAt: string
  createdBy: string | null
}

export interface RegisterRequest {
  kind: string
  name: string
  description?: string
  producedByExecutionId?: string
}

export interface PublishVersionRequest {
  contentUri: string
  sizeBytes?: number
  checksumSha256?: string
  itemCount?: number
  producedByExecutionId?: string
  derivedFromVersionId?: string
  metadataJson?: string
}

export interface EdgeRequest {
  fromType: string
  fromId: string
  relation: string
  toType: string
  toId: string
  toLabel?: string
  confidence?: number
  origin?: string
}
