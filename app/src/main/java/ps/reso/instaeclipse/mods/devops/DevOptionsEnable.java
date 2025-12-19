package ps.reso.instaeclipse.mods.devops;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class DevOptionsEnable {

    public void handleDevOptions(DexKitBridge bridge) {
        try {
            findAndHookDynamicMethod(bridge);
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error handling Dev Options: " + e.getMessage());
        }
    }

    private void findAndHookDynamicMethod(DexKitBridge bridge) {
        try {
            // Step 1: Find classes referencing "is_employee"
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("is_employee"))
            );

            if (classes.isEmpty()) {
                XposedBridge.log("(InstaEclipse | DevOptionsEnable): ⚠️ No classes found with 'is_employee' string");
                return;
            }

            boolean hooked = false;
            for (ClassData classData : classes) {
                String className = classData.getName();
                if (!className.startsWith("X.")) continue;

                // Step 2: Find methods referencing "is_employee" within the class
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(className)
                                .usingStrings("is_employee"))
                );

                if (methods.isEmpty()) continue;

                for (MethodData method : methods) {
                    if (inspectInvokedMethods(bridge, method)) {
                        hooked = true;
                        return; // Exit after first successful hook
                    }
                }
            }
            
            if (!hooked) {
                XposedBridge.log("(InstaEclipse | DevOptionsEnable): ⚠️ No suitable methods found to hook");
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error during discovery: " + e.getMessage());
        }
    }

    private boolean inspectInvokedMethods(DexKitBridge bridge, MethodData method) {
        try {
            List<MethodData> invokedMethods = method.getInvokes();
            if (invokedMethods.isEmpty()) return false;

            for (MethodData invokedMethod : invokedMethods) {
                String returnType = String.valueOf(invokedMethod.getReturnType());

                if (!returnType.contains("boolean")) continue;

                List<String> paramTypes = new java.util.ArrayList<>();
                for (Object param : invokedMethod.getParamTypes()) {
                    paramTypes.add(String.valueOf(param));
                }

                if (paramTypes.size() == 1 &&
                        paramTypes.get(0).contains("com.instagram.common.session.UserSession")) {

                    String targetClass = invokedMethod.getClassName();
                    XposedBridge.log("(InstaEclipse | DevOptionsEnable): 📦 Hooking boolean methods in: " + targetClass);
                    return hookAllBooleanMethodsInClass(bridge, targetClass);
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error inspecting invoked methods: " + e.getMessage());
        }
        return false;
    }

    private boolean hookAllBooleanMethodsInClass(DexKitBridge bridge, String className) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass(className))
            );

            boolean hooked = false;
            for (MethodData method : methods) {
                String returnType = String.valueOf(method.getReturnType());
                List<String> paramTypes = new java.util.ArrayList<>();
                for (Object param : method.getParamTypes()) {
                    paramTypes.add(String.valueOf(param));
                }

                if (returnType.contains("boolean") &&
                        paramTypes.size() == 1 &&
                        paramTypes.get(0).contains("com.instagram.common.session.UserSession")) {

                    try {
                        Method targetMethod = method.getMethodInstance(Module.hostClassLoader);

                        XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (FeatureFlags.isDevEnabled) {
                                    param.setResult(true);
                                    FeatureStatusTracker.setHooked("DevOptions");
                                }
                            }
                        });

                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ✅ Hooked: " +
                                method.getClassName() + "." + method.getName());
                        hooked = true;

                    } catch (Throwable e) {
                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Failed to hook " + method.getName() + ": " + e.getMessage());
                    }
                }
            }

            return hooked;

        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error while hooking class: " + className + " → " + e.getMessage());
            return false;
        }
    }
}
