/** 业务术语（P2）。 */
export type GlossaryStatus = 'DRAFT' | 'PUBLISHED' | 'DEPRECATED'

export interface GlossaryTerm {
  id: string
  term: string
  definition: string
  synonyms: string[]
  domainId: string | null
  domainName: string | null
  status: GlossaryStatus
  ownerUsername: string | null
  publishedBy: string | null
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  bindingCount: number
}

export interface GlossaryTermRequest {
  term: string
  definition: string
  synonyms: string[]
  domainId?: string | null
  ownerUsername?: string | null
}

export interface GlossaryBinding {
  id: string
  termId: string
  datasetId: string
  datasetName: string | null
  datasetQualifiedName: string | null
  columnId: string | null
  columnName: string | null
  note: string | null
  createdAt: string
}

export interface GlossaryBindingRequest {
  datasetId: string
  columnId?: string | null
  note?: string | null
}

export interface AssetGlossaryTerm {
  termId: string
  term: string
  definition: string
  synonyms: string[]
  domainName: string | null
  bindingId: string
  columnId: string | null
  columnName: string | null
  note: string | null
}
