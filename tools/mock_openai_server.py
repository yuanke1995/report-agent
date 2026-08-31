#!/usr/bin/env python3
"""
模拟 OpenAI 兼容服务端，用于在没有真实模型的情况下验证 Agent 全链路。

这次模拟的是一个「犯了错又被模型自己纠正」的完整 Agent 轨迹：
  第 1 次请求  → 模型决定写 SQL（execute_sql），但列名写错（pay_amnt）
  第 2 次请求  → 工具返回结构化错误，模型读懂后修正（pay_amount），重试
  第 3 次请求  → 执行成功，模型基于真实数据给出最终回答

它证明的是这条链路通了：
  工具 schema 发出 → tool_call 收到 → Java 执行（含 SqlGuard 拒绝）
  → 结构化错误回灌 → 模型修正 → 重试成功 → 最终回答

用法：python3 tools/mock_openai_server.py [端口]
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
STATE = {"calls": 0}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        # Spring AI 的 RestClient 用 chunked 编码发送请求体，需按 chunked 协议解析
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length > 0 else self._read_chunked()
        body = json.loads(raw or b"{}")
        STATE["calls"] += 1
        n = STATE["calls"]
        # 循环模式：请求序号对 3 取模（1→错误SQL, 2→修正SQL, 0→最终回答）。
        # 这样任何时刻发起测试都能看到完整链路，不会被之前的测试耗尽计数。
        n = STATE["calls"] % 3 or 3

        print(f"\n[mock] === 第 {STATE['calls']} 次请求（周期内第 {n} 次）===", flush=True)
        tools = body.get("tools") or []
        print(f"[mock] tools 数量: {len(tools)}", flush=True)
        for m in body.get("messages", []):
            role = m.get("role")
            content = (m.get("content") or "")
            if role == "tool":
                print(f"[mock]   tool 回灌: {content[:120]}…", flush=True)
            else:
                print(f"[mock]   {role}: {len(content)} 字符", flush=True)

        if n == 1:
            resp = self._first_bad_sql()
        elif n == 2:
            resp = self._fixed_sql()
        else:
            resp = self._final_answer(body)

        payload = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _read_chunked(self):
        chunks = []
        while True:
            line = self.rfile.readline().strip()
            if not line:
                break
            try:
                size = int(line, 16)
            except ValueError:
                break
            if size == 0:
                self.rfile.readline()
                break
            chunks.append(self.rfile.read(size))
            self.rfile.readline()
        return b"".join(chunks)

    def _tool_call(self, tool_call_id, name, arguments):
        return {
            "id": f"chatcmpl-mock-{STATE['calls']}",
            "object": "chat.completion",
            "created": 0,
            "model": "mock",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [{
                        "id": tool_call_id,
                        "type": "function",
                        "function": {"name": name, "arguments": json.dumps(arguments)},
                    }],
                },
                "finish_reason": "tool_calls",
            }],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120},
        }

    def _first_bad_sql(self):
        """第 1 轮：模型写出错误列名的 SQL——模拟真实模型常犯的错"""
        print("[mock] → 第 1 轮：调用 execute_sql（列名拼错 pay_amnt）", flush=True)
        return self._tool_call("call_bad_sql", "execute_sql",
                               {"sql": "SELECT pay_amnt FROM fact_order LIMIT 5"})

    def _fixed_sql(self):
        """第 2 轮：模型读懂了 SqlGuard 的结构化错误，修正列名后重试。
        返回多列聚合 SQL：既验证列校验，也让前端能渲染表格 + 折线图。"""
        print("[mock] → 第 2 轮：调用 execute_sql（修正为多列聚合）", flush=True)
        return self._tool_call("call_fixed_sql", "execute_sql",
                               {"sql": "SELECT DATE_FORMAT(o.order_date, '%Y-%m') AS 月份, "
                                       "ROUND(SUM(o.pay_amount), 2) AS 销售额 "
                                       "FROM fact_order o "
                                       "WHERE o.order_status IN ('paid', 'shipped', 'completed') "
                                       "GROUP BY DATE_FORMAT(o.order_date, '%Y-%m') "
                                       "ORDER BY 月份 LIMIT 6"})

    def _final_answer(self, body):
        """第 3 轮：基于真实数据给出最终回答"""
        tool_msgs = [m for m in body.get("messages", []) if m.get("role") == "tool"]
        has_data = any("| 3755" in (m.get("content") or "") for m in tool_msgs)
        rows = 0
        for m in tool_msgs:
            for line in (m.get("content") or "").split("\\n"):
                if line.startswith("| ") and "pay_amount" not in line and line.count("|") >= 3:
                    rows += 1
        text = f"查询成功，返回 {max(rows, 0)} 行数据。真实数据示例：3755.87。"
        if has_data:
            text += " 数据来自真实库。"
        print(f"[mock] → 最终回答：{text}", flush=True)
        return {
            "id": f"chatcmpl-mock-{STATE['calls']}",
            "object": "chat.completion",
            "created": 0,
            "model": "mock",
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": text},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 200, "completion_tokens": 30, "total_tokens": 230},
        }


if __name__ == "__main__":
    print(f"[mock] OpenAI 兼容服务已启动: http://127.0.0.1:{PORT}", flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
