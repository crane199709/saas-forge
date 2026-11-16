import type { PlatformClient } from '@crane199709/sdk'

export type AiModelProvider = 'DEEPSEEK' | 'OPENAI_COMPATIBLE'
export type AiModelCapability = 'CHAT' | 'TOOLS' | 'VISION' | 'REASONING'
export type AiModelStatus = 'ENABLED' | 'DISABLED'
export interface AiModelContent {
  name: string
  provider: AiModelProvider
  modelId: string
  baseUrl: string
  capabilities: AiModelCapability[]
  contextWindow: number
  status: AiModelStatus
}
export interface AiModelWrite extends AiModelContent {
  apiKey?: string
}
export interface AiModel extends AiModelContent {
  id: string
  credentialKind?: 'API_KEY' | 'REFERENCE' | null
}

export function createAiModelApi(client: Pick<PlatformClient, 'api'>) {
  const base = '/ai-platform/models'
  return {
    list: () => client.api<AiModel[]>(base),
    update: (id: string, request: AiModelWrite) =>
      client.api<AiModel>(`${base}/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      }),
    create: (request: AiModelWrite) =>
      client.api<AiModel>(base, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      }),
  }
}
export type AiModelApi = ReturnType<typeof createAiModelApi>
