// Credentials are supplied through stdin by the private-instance smoke test.
// Uses the Node built-in WebSocket; no dependency installation is needed.
import assert from 'node:assert/strict'

let input = ''
for await (const chunk of process.stdin) input += chunk
const { port, first, second, otherId } = JSON.parse(input)
const sockets = []
const open = token => new Promise((resolve, reject) => {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/ws/farm-status?token=${encodeURIComponent(token)}`)
  sockets.push(socket)
  const timeout = setTimeout(() => reject(new Error('Private WebSocket open timed out')), 10000)
  socket.addEventListener('message', () => { clearTimeout(timeout); resolve(socket) }, { once: true })
  socket.addEventListener('error', () => { clearTimeout(timeout); reject(new Error('Private WebSocket connection failed')) }, { once: true })
})

try {
  const firstSocket = await open(first)
  const secondSocket = await open(second)
  const closed = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Revoked WebSocket was not closed')), 10000)
    secondSocket.addEventListener('close', event => { clearTimeout(timeout); resolve(event.code) }, { once: true })
  })
  const response = await fetch(`http://127.0.0.1:${port}/api/v1/auth/sessions/${otherId}/revoke`, {
    method: 'POST', headers: { Authorization: `Bearer ${first}` }
  })
  assert.equal(response.status, 200)
  assert.equal(await closed, 1008)
  assert.equal(firstSocket.readyState, WebSocket.OPEN)
  console.log('PASS: real WebSocket single-session revocation preserves another connection.')
} finally {
  for (const socket of sockets) socket.close()
}
