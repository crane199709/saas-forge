-- 只由显式 demo profile 加载：本地 Shell 无需先在 platform-console 登记即可加载示例 Sub-App（pnpm start:demo 启动在 5175）。
-- 这是 App Registry 副本的种子，revision 为 0：在 platform-console 登记或修改 demo 后，广播的 SubAppChanged 随即覆盖它。
INSERT INTO sub_app (code, name, version, revision) VALUES ('demo', '示例子应用', 'local', 0) ON CONFLICT DO NOTHING;
INSERT INTO sub_app_entry (code, environment, remote_entry)
SELECT 'demo', 'local', 'http://127.0.0.1:5175/remoteEntry.js' FROM sub_app WHERE code = 'demo' AND revision = 0
ON CONFLICT DO NOTHING;
