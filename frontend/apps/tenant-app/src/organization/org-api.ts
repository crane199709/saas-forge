import type { Page, TenantClient } from '@crane199709/sdk'

/** IAM 组织接口的模型，由 tenant-app 维护，不属于通用 SDK。 */
export interface Department {
  id: string
  parentId: string | null
  name: string
  path: string
}

export interface DepartmentNode extends Department {
  children: DepartmentNode[]
}

export interface Position {
  id: string
  name: string
}

export type AttributeType = 'string' | 'number' | 'boolean'
export type AttributeValue = string | number | boolean

export interface ExtendedAttribute {
  id: string
  code: string
  name: string
  type: AttributeType
  required: boolean
}

export interface MemberAssignment {
  membershipId: string
  account: string
  displayName: string
  status: string
  primaryDepartmentId: string | null
  /** 兼任部门，不含主部门。 */
  departmentIds: string[]
  positionIds: string[]
  attributes: Record<string, AttributeValue>
}

export type Assignment = Pick<
  MemberAssignment,
  'primaryDepartmentId' | 'departmentIds' | 'positionIds' | 'attributes'
>

/** 接口按路径排序返回扁平列表，这里按上级组装为树；上级已不可见的部门作为根展示。 */
export function departmentTree(departments: Department[]): DepartmentNode[] {
  const nodes = new Map(departments.map((item) => [item.id, { ...item, children: [] }]))
  const roots: DepartmentNode[] = []
  for (const node of nodes.values()) {
    const parent = node.parentId ? nodes.get(node.parentId) : undefined
    ;(parent ? parent.children : roots).push(node)
  }
  return roots
}

/** 组织管理接口；只有 Owner 与 Tenant Admin 可以调用，其他成员得到 403。 */
export function createOrganizationApi(client: Pick<TenantClient, 'api' | 'page'>) {
  // 经网关的 IAM 路由，与 `/iam/auth` 同源
  const base = '/iam/org'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

  return {
    departments: () => send<Department[]>('GET', '/departments'),
    createDepartment: (name: string, parentId: string | null) =>
      send<Department>('POST', '/departments', { name, parentId }),
    renameDepartment: (id: string, name: string) =>
      send<Department>('PUT', `/departments/${id}`, { name }),
    moveDepartment: (id: string, parentId: string | null) =>
      send<Department>('PUT', `/departments/${id}/parent`, { parentId }),
    deleteDepartment: (id: string) => send<null>('DELETE', `/departments/${id}`),
    positions: () => send<Position[]>('GET', '/positions'),
    createPosition: (name: string) => send<Position>('POST', '/positions', { name }),
    renamePosition: (id: string, name: string) =>
      send<Position>('PUT', `/positions/${id}`, { name }),
    deletePosition: (id: string) => send<null>('DELETE', `/positions/${id}`),
    attributes: () => send<ExtendedAttribute[]>('GET', '/extended-attributes'),
    createAttribute: (attribute: Omit<ExtendedAttribute, 'id'>) =>
      send<ExtendedAttribute>('POST', '/extended-attributes', attribute),
    updateAttribute: (id: string, name: string, required: boolean) =>
      send<ExtendedAttribute>('PUT', `/extended-attributes/${id}`, { name, required }),
    deleteAttribute: (id: string) => send<null>('DELETE', `/extended-attributes/${id}`),
    members: (page: number, size: number): Promise<Page<MemberAssignment>> =>
      client.page<MemberAssignment>(`${base}/memberships?page=${page}&size=${size}`),
    assign: (membershipId: string, assignment: Assignment) =>
      send<null>('PUT', `/memberships/${membershipId}/assignment`, assignment),
  }
}

export type OrganizationApi = ReturnType<typeof createOrganizationApi>
