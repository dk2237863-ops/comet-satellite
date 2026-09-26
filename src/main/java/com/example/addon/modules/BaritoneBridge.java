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
 * 现在只用来把 #scan 指令注册进 Baritone 的指令系统，方便直接在聊天栏用 #scan 调用；
 * 飞行、寻路、补给全部由 SatelliteScanner 内部的 ElytraPilot 自己完成，不依赖 Baritone 的寻路/鞘翅模块。
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
