"""Neo4j 图数据体检脚本 —— 验证容器重建后数据是否完好。

用法：python verify_neo4j.py
依赖：仅标准库（走 Neo4j HTTP API，无需 driver）
"""
import base64
import json
import urllib.request

BASE = "http://localhost:7474/db/neo4j/tx/commit"
AUTH = base64.b64encode(b"neo4j:root1234").decode()


def q(cypher: str) -> dict:
    payload = json.dumps({"statements": [{"statement": cypher}]}).encode()
    req = urllib.request.Request(
        BASE,
        data=payload,
        headers={"Content-Type": "application/json", "Authorization": "Basic " + AUTH},
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def main() -> None:
    print("=== 节点标签分布 ===")
    d = q("MATCH (n) RETURN labels(n)[0] AS label, count(*) AS cnt ORDER BY cnt DESC")
    node_total = 0
    for row in d["results"][0]["data"]:
        print(f"  {str(row['row'][0]):<20} {row['row'][1]}")
        node_total += row["row"][1]
    print(f"  {'节点合计':<20} {node_total}")

    print("\n=== 关系类型分布 ===")
    d = q("MATCH ()-[r]->() RETURN type(r) AS t, count(*) AS cnt ORDER BY cnt DESC")
    edge_total = 0
    for row in d["results"][0]["data"]:
        print(f"  {str(row['row'][0]):<20} {row['row'][1]}")
        edge_total += row["row"][1]
    print(f"  {'关系合计':<20} {edge_total}")

    print("\n=== 样例关系 ===")
    d = q("MATCH (a)-[r]->(b) RETURN a.name, type(r), b.name LIMIT 8")
    if d.get("errors"):
        print("  查询报错:", d["errors"])
    else:
        for row in d["results"][0]["data"]:
            print(f"  {row['row'][0]} -[{row['row'][1]}]-> {row['row'][2]}")


if __name__ == "__main__":
    main()
