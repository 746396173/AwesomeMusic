package com.yishi.core.rxbinding

import io.reactivex.Observable
import io.reactivex.Observer

/**
 * @author:kaibo
 * @date 2019/5/20 15:50
 * @GitHub：https://github.com/yuxuelian
 * @qq：568966289
 * @description：
 */

abstract class InitialValueObservable<T> : Observable<T>() {
    protected abstract val initialValue: T

    override fun subscribeActual(observer: Observer<in T>) {
        subscribeListener(observer)
        observer.onNext(initialValue)
    }

    protected abstract fun subscribeListener(observer: Observer<in T>)

    fun skipInitialValue(): Observable<T> = Skipped()

    private inner class Skipped : Observable<T>() {
        override fun subscribeActual(observer: Observer<in T>) {
            subscribeListener(observer)
        }
    }
}