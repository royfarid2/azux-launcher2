#!/bin/sh
# Host test of the GLFW shim + symbol coverage against LWJGL's GLFW.java (pass the LWJGL checkout as $1, optional)
set -e
cd "$(dirname "$0")"
CPP=../../app/src/main/cpp
gcc -std=gnu11 -Wall -Wextra -g -fsanitize=address,undefined -fvisibility=hidden $CPP/glfw_shim.c $CPP/platform_fake.c shim_test.c -o /tmp/shim_test -lpthread -ldl
/tmp/shim_test
gcc -std=gnu11 -shared -fPIC -fvisibility=hidden $CPP/glfw_shim.c $CPP/platform_fake.c -o /tmp/libglfw_shim.so -Dazux_log=azux_log_stub 2>/dev/null || \
gcc -std=gnu11 -shared -fPIC -fvisibility=hidden -Wl,--unresolved-symbols=ignore-all $CPP/glfw_shim.c $CPP/platform_fake.c -o /tmp/libglfw_shim.so
if [ -n "$1" ]; then
  F=$(find "$1" -name GLFW.java -path '*org/lwjgl/glfw*' | head -1)
  grep -o '"glfw[A-Za-z0-9][A-Za-z0-9]*"' "$F" | tr -d '"' | sort -u > /tmp/lwjgl_glfw_funcs.txt
  nm -D --defined-only /tmp/libglfw_shim.so | awk '{print $3}' | grep '^glfw' | sort -u > /tmp/shim_funcs.txt
  echo "LWJGL asks for $(wc -l < /tmp/lwjgl_glfw_funcs.txt) GLFW functions, shim exports $(wc -l < /tmp/shim_funcs.txt)"
  MISSING=$(comm -23 /tmp/lwjgl_glfw_funcs.txt /tmp/shim_funcs.txt)
  if [ -n "$MISSING" ]; then echo "MISSING in shim:"; echo "$MISSING"; exit 1; fi
  echo "symbol coverage: complete"
fi
