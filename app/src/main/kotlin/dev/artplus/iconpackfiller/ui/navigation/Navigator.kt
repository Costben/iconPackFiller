package dev.artplus.iconpackfiller.ui.navigation

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * 返回栈持有者。由 MainViewModel 持有（backstack 是导航的唯一事实源）。
 *
 * 仅暴露导航语义操作，业务代码不直接操作列表。
 */
class Navigator(start: Route) {

    private val _backStack = listOf(start).toMutableStateList()
    val backStack: SnapshotStateList<Route> = _backStack

    val current: Route get() = _backStack.last()

    fun push(route: Route) {
        if (_backStack.lastOrNull() == route) return
        _backStack.add(route)
    }

    fun pop(): Boolean {
        if (_backStack.size <= 1) return false
        _backStack.removeAt(_backStack.lastIndex)
        return true
    }

    /** 用 [routes] 整体替换返回栈（保证非空）。 */
    fun resetTo(vararg routes: Route) {
        require(routes.isNotEmpty()) { "返回栈不能为空" }
        _backStack.clear()
        _backStack.addAll(routes)
    }

    fun popTo(route: Route) {
        val index = _backStack.indexOf(route)
        if (index < 0) return
        while (_backStack.lastIndex > index) {
            _backStack.removeAt(_backStack.lastIndex)
        }
    }
}
