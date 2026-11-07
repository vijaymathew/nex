# A string-keyed map: 500k keys built with +, put, then each read back.
import time
def run(n):
    m = {}
    for i in range(n):
        m["k" + str(i)] = i
    total = 0
    for i in range(n):
        total = (total + m["k" + str(i)]) % 1000003
    return total
t0 = time.perf_counter_ns()
r = run(500000)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
