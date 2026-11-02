(module
 (import "env" "memory" (memory 1 4 shared))
 (export "memory" (memory 0))
 (func (export "add") (param i32) (result i32) i32.const 0 local.get 0 i32.atomic.rmw.add)
 (func (export "load") (result i32) i32.const 0 i32.atomic.load)
 (func (export "store") (param i32) i32.const 0 local.get 0 i32.atomic.store)
 (func (export "load64") (result i64) i32.const 8 i64.atomic.load)
 (func (export "store64") (param i64) i32.const 8 local.get 0 i64.atomic.store)
 (func (export "widths") (result i32)
  i32.const 16 i32.const 255 i32.atomic.store8
  i32.const 16 i32.const 1 i32.atomic.rmw8.add_u drop
  i32.const 18 i32.const 65535 i32.atomic.store16
  i32.const 18 i32.const 1 i32.atomic.rmw16.add_u drop
  i32.const 20 i64.const 4294967295 i64.atomic.store32
  i32.const 20 i64.const 1 i64.atomic.rmw32.add_u drop
  i32.const 16 i32.atomic.load8_u i32.const 18 i32.atomic.load16_u i32.or
  i32.const 20 i32.atomic.load i32.or)
 (func (export "cmp") (param i32 i32) (result i32)
  i32.const 0 local.get 0 local.get 1 i32.atomic.rmw.cmpxchg)
 (func (export "wait") (param i32 i64) (result i32)
  i32.const 0 local.get 0 local.get 1 memory.atomic.wait32)
 (func (export "wait64") (param i64 i64) (result i32)
  i32.const 8 local.get 0 local.get 1 memory.atomic.wait64)
 (func (export "notify") (param i32) (result i32) i32.const 0 local.get 0 memory.atomic.notify)
 (func (export "notify64") (param i32) (result i32) i32.const 8 local.get 0 memory.atomic.notify)
 (func (export "grow") (param i32) (result i32) local.get 0 memory.grow)
 (func (export "fence") atomic.fence)
 (func (export "misaligned") (result i64) i32.const 1 i64.atomic.load)
 (func (export "bounds") (result i32) i32.const 262144 i32.atomic.load))
