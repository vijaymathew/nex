# Startup: an empty program.
import time
t0 = time.perf_counter_ns()
r = 0
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
