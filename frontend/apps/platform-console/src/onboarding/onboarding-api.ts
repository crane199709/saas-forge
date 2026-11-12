import type { PlatformClient } from '@crane199709/sdk'
export interface ReportingStatus {
  enabled: boolean
  reportedVersion?: string | null
  lastSuccessAt?: string | null
  lastFailure?: string | null
  lastFailureAt?: string | null
  attempts: number
  phase: string
}
export interface ServiceDiagnostic {
  service: string
  configuration: {
    status: string
    source: string
    checkedAt: string
    prefix?: string | null
    missing: string[]
  }
  runtime: { status: string; checkedAt: string; reporting?: ReportingStatus | null }
  request: { status: string; checkedAt?: string | null; detail?: string | null }
}
export interface OnboardingDiagnostics {
  directoryAvailable: boolean
  services: ServiceDiagnostic[]
}
export const createOnboardingApi = (client: Pick<PlatformClient, 'api'>) => ({
  read: () => client.api<OnboardingDiagnostics>('/platform/onboarding'),
})
export type OnboardingApi = ReturnType<typeof createOnboardingApi>
