# Integer arithmetic in a loop: 50M iterations of *, + and %.
import time
def run(n):
    acc = 0
    for i in range(n):
        acc = (acc + i * i) % 1000003
    return acc
t0 = time.perf_counter_ns()
r = run(50000000)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
