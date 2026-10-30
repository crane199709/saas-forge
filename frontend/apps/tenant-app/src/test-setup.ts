// jsdom 没有 Web Locks；按锁名排队的桩实现与浏览器一样让同名锁的回调依次执行，多个客户端实例可借此模拟多个标签页。
const queues = new Map<string, Promise<unknown>>()
Object.defineProperty(navigator, 'locks', {
  configurable: true,
  value: {
    request(name: string, callback: () => Promise<unknown>) {
      const result = (queues.get(name) ?? Promise.resolve()).then(() => callback())
      queues.set(
        name,
        result.catch(() => undefined),
      )
      return result
    },
  },
})
