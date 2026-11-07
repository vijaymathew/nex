# Closure (function value) calls: 20M.
import time
def run(n):
    f = lambda x: x + 1
    total = 0
    for i in range(n):
        total = (total + f(i)) % 1000003
    return total
t0 = time.perf_counter_ns()
r = run(20000000)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
