package io.github.mangi.eta.ui.navigation

import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey

class AgentNavigator(
    val backStack: NavBackStack,
) {
    fun push(route: AppRoute) {
        if (route in backStack) {
            // 同一个 key 在返回栈里出现两次会直接崩溃；目标若是首页则退回首页，等价于「再回一次主页」。
            if (route is AppRoute.Home) popToHome()
            return
        }
        backStack.add(route)
    }

    fun replace(route: AppRoute) {
        val existingIndex = backStack.indexOf(route)
        if (existingIndex >= 0) {
            while (backStack.lastIndex > existingIndex) {
                backStack.removeLastOrNull()
            }
            return
        }

        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = route
        } else {
            backStack.add(route)
        }
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeLastOrNull()
        return true
    }

    fun popToHome() {
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        backStack.firstOrNull()?.let { first ->
            if (first !is AppRoute.Home) {
                backStack.clear()
                backStack.add(AppRoute.Home)
            }
        }
    }

    fun current(): NavKey? = backStack.lastOrNull()
}
