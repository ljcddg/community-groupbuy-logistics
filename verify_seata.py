# -*- coding: utf-8 -*-
"""Seata AT 严格验证：场景A 库存不足应全局回滚；场景B 库存充足应提交。"""
import http.client, json, subprocess, sys, time

sys.stdout.reconfigure(encoding='utf-8')
MYSQL = r'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.EXE'


def api(path, body=None):
    c = http.client.HTTPConnection('127.0.0.1', 8102, timeout=25)
    c.request('POST', path, body=json.dumps(body) if body is not None else None,
              headers={'Content-Type': 'application/json'})
    r = c.getresponse()
    return r.read().decode('utf-8', errors='replace')[:270]


def sql(q):
    r = subprocess.run([MYSQL, '-uroot', '-proot', '--default-character-set=utf8mb4',
                        '-D', 'community_logistics', '-e', q], capture_output=True)
    return r.stdout.decode('utf-8', errors='replace').strip()


def one(q):
    rows = sql(q).splitlines()
    return rows[-1].strip() if len(rows) > 1 else '?'


print('调用前：配送单3 status =', one('SELECT status FROM t_delivery WHERE id=3'))
print('调用前：异常单2 need_reship =', one('SELECT need_reship FROM t_delivery_exception WHERE id=2'))

print()
print('===== 场景 A：库存不足（0 件）→ 期望 业务码4002 + 全局回滚 =====')
sql('UPDATE t_stock SET total=0 WHERE sku_id=1001')
print('  设置库存 =', one('SELECT total FROM t_stock WHERE sku_id=1001'))
print('  调用 /delivery/3/reship-seata ...')
print('  响应 :', api('/delivery/3/reship-seata'))
time.sleep(2)
a = one('SELECT status FROM t_delivery WHERE id=3')
print('  调用后 status =', a, '|', '✅ 已回滚到 5（异常）' if a == '5' else f'❌ 状态={a}')
print('  库存是否被动过 =', one('SELECT total FROM t_stock WHERE sku_id=1001'))

print()
print('===== 场景 B：库存充足（1000 件）→ 期望 提交成功 =====')
sql('UPDATE t_stock SET total=1000 WHERE sku_id=1001')
print('  设置库存 =', one('SELECT total FROM t_stock WHERE sku_id=1001'))
print('  调用 /delivery/3/reship-seata ...')
print('  响应 :', api('/delivery/3/reship-seata'))
time.sleep(2)
b = one('SELECT status FROM t_delivery WHERE id=3')
print('  调用后 status =', b, '|', '✅ 提交成功（7=补发中）' if b == '7' else f'⚠️ 状态={b}')
print('  库存 =', one('SELECT total FROM t_stock WHERE sku_id=1001'), '（期望 999）')

print()
print('===== 轨迹（关键证据）=====')
print(sql('SELECT from_status AS 从, to_status AS 到, remark AS 备注 FROM t_delivery_trace WHERE delivery_id=3 ORDER BY id'))
print()
print('undo_log 残留行数（期望 0，二阶段已清理） =', one('SELECT COUNT(*) FROM undo_log'))
print('全局事务表 global_table 行数 =', one('SELECT COUNT(*) FROM seata.global_table'))
print('全局锁表 lock_table 行数 =', one('SELECT COUNT(*) FROM seata.lock_table'))
