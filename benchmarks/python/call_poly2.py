# Polymorphic calls of a method over 2 classes: 20M.
import time
class Shape:
    def value(self, x): raise NotImplementedError
class S1(Shape):
    def value(self, x):
        return x + 1
class S2(Shape):
    def value(self, x):
        return x + 2
def run(n):
    shapes, total = [S1(), S2()], 0
    for i in range(n):
        total = (total + shapes[i % 2].value(i)) % 1000003
    return total
t0 = time.perf_counter_ns()
r = run(20000000)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
