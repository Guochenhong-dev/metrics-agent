#!/usr/bin/env python3
"""本地模型协议桩，只验证Spring AI解析与工具循环，不代表真实模型效果。"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            while True:
                size = int(self.rfile.readline().strip().split(b';')[0], 16)
                if not size:
                    while self.rfile.readline().strip():
                        pass
                    break
                chunks.append(self.rfile.read(size)); self.rfile.read(2)
            payload = b''.join(chunks)
        else:
            payload = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        body = json.loads(payload)
        tools = body.get('tools') or []
        messages = body.get('messages') or []
        results = [m for m in messages if m['role'] == 'tool']
        if tools and (len(results) < 2 or os.getenv('FAKE_LOOP') == 'true'):
            evidence = 'E1' if not results else 'E2'
            message = {'role': 'assistant', 'content': None, 'tool_calls': [{'id': 'call_' + str(len(results)), 'type': 'function', 'function': {'name': 'getEvidence', 'arguments': json.dumps({'evidenceId': evidence})}}]}
            reason = 'tool_calls'
            print('STUB requested evidence', evidence, flush=True)
        elif tools:
            assert 'queries' in json.dumps(results), results
            narrative = {'summary': '变化集中在部分分类，需要结合口径与证据继续核查。', 'hypotheses': [{'text': '分类的订单和退款结构变化可能与总体波动相关。', 'evidenceIds': ['E1', 'E2'], 'validation': '固定其他维度，核对订单与退款来源后再判断。'}]}
            if os.getenv('FAKE_INVALID') == 'true':
                narrative['hypotheses'][0]['evidenceIds'] = ['E99']
            message = {'role': 'assistant', 'content': json.dumps(narrative, ensure_ascii=False)}
            reason = 'stop'
            print('STUB received evidence and returned narrative', flush=True)
        else:
            plan = {'metric': 'net_receipts', 'dimension': 'region', 'startDate': '2026-08-25', 'endDate': '2026-08-31', 'region': None, 'category': None, 'channel': None}
            message = {'role': 'assistant', 'content': json.dumps(plan)}
            reason = 'stop'
            print('STUB returned plan', flush=True)
        reply = {'id': 'metrics-stub', 'object': 'chat.completion', 'created': 1700000000, 'model': 'test-stub', 'choices': [{'index': 0, 'message': message, 'finish_reason': reason}], 'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30}}
        data = json.dumps(reply, ensure_ascii=False).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)

if __name__ == '__main__':
    print('Model protocol stub: http://127.0.0.1:8098', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 8098), Handler).serve_forever()
