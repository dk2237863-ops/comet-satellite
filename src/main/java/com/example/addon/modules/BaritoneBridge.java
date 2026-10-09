package com.example.addon.modules;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 通过反射调用 Baritone API：不需要改 build 配置，没装 Baritone 时所有方法都会安全地返回 false。
 */
final class BaritoneBridge {
    private volatile String lastError = "";

    String lastError() {
        return lastError;
    }

    boolean available() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object primary() throws Exception {
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Object provider = api.getMethod("getProvider").invoke(null);
        Class<?> providerCls = Class.forName("baritone.api.IBaritoneProvider");
        Object baritone = providerCls.getMethod("getPrimaryBaritone").invoke(provider);
        if (baritone == null) throw new IllegalStateException("Baritone 还没有初始化");
        return baritone;
    }

    /** 让 Baritone 走到指定的 X/Z（只看水平坐标）。 */
    boolean goToXZ(int x, int z) {
        try {
            Object baritone = primary();
            Object process = Class.forName("baritone.api.IBaritone")
                .getMethod("getCustomGoalProcess").invoke(baritone);
            Class<?> goalCls = Class.forName("baritone.api.pathing.goals.Goal");
            Object goal = Class.forName("baritone.api.pathing.goals.GoalXZ")
                .getConstructor(int.class, int.class).newInstance(x, z);
            Class.forName("baritone.api.process.ICustomGoalProcess")
                .getMethod("setGoalAndPath", goalCls).invoke(process, goal);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    boolean stop() {
        try {
            Object baritone = primary();
            Object behavior = Class.forName("baritone.api.IBaritone")
                .getMethod("getPathingBehavior").invoke(baritone);
            Class.forName("baritone.api.behavior.IPathingBehavior")
                .getMethod("cancelEverything").invoke(behavior);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    boolean isPathing() {
        try {
            Object baritone = primary();
            Object behavior = Class.forName("baritone.api.IBaritone")
                .getMethod("getPathingBehavior").invoke(baritone);
            Object r = Class.forName("baritone.api.behavior.IPathingBehavior")
                .getMethod("isPathing").invoke(behavior);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** 向 Baritone 注册一条自定义指令（用 # 前缀调用）。 */
    boolean registerCommand(List<String> names, String shortDesc, List<String> longDesc, Consumer<String> handler) {
        try {
            Class<?> commandCls = Class.forName("baritone.api.command.ICommand");
            Class<?> argsCls = Class.forName("baritone.api.command.argument.IArgConsumer");
            Method rawRest = argsCls.getMethod("rawRest");

            InvocationHandler ih = (proxy, method, args) -> {
                String n = method.getName();
                if (n.equals("execute")) {
                    String raw = String.valueOf(rawRest.invoke(args[1]));
                    handler.accept(raw);
                    return null;
                }
                if (n.equals("tabComplete")) return Stream.empty();
                if (n.equals("getShortDesc")) return shortDesc;
                if (n.equals("getLongDesc")) return longDesc;
                if (n.equals("getNames")) return names;
                if (n.equals("hiddenFromHelp")) return false;
                if (n.equals("toString")) return "SatelliteScanCommand";
                if (n.equals("hashCode")) return System.identityHashCode(proxy);
                if (n.equals("equals")) return proxy == args[0];
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);

                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                return null;
            };

            Object command = Proxy.newProxyInstance(commandCls.getClassLoader(), new Class<?>[]{commandCls}, ih);

            Object baritone = primary();
            Object manager = Class.forName("baritone.api.IBaritone")
                .getMethod("getCommandManager").invoke(baritone);
            Object registry = Class.forName("baritone.api.command.manager.ICommandManager")
                .getMethod("getRegistry").invoke(manager);

            Method register = null;
            for (Method m : Class.forName("baritone.api.command.registry.Registry").getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 1) {
                    register = m;
                    break;
                }
            }
            if (register == null) throw new NoSuchMethodException("Registry.register");
            register.invoke(registry, command);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    private void fail(Throwable t) {
        Throwable c = t;
        if (t instanceof InvocationTargetException && t.getCause() != null) c = t.getCause();
        lastError = c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
