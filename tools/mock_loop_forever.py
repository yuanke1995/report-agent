#!/usr/bin/env python3
"""临时验证脚本：永远返回 tool_call 的 mock，用于验证 Agent 轮次上限的优雅终止。"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
STATE = {"calls": 0}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length > 0 else self._read_chunked()
        STATE["calls"] += 1
        n = STATE["calls"]
        print(f"[mock] 第 {n} 次请求：继续返回 tool_call", flush=True)

        resp = {
            "id": f"chatcmpl-{n}",
            "object": "chat.completion",
            "created": 0,
            "model": "mock",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [{
                        "id": f"call_{n}",
                        "type": "function",
                        "function": {
                            "name": "get_table_schema",
                            "arguments": json.dumps({"tables": "fact_order"}),
                        },
                    }],
                },
                "finish_reason": "tool_calls",
            }],
            "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110},
        }
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


if __name__ == "__main__":
    print(f"[mock] 无限 tool_call 服务: http://127.0.0.1:{PORT}", flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
