package ua.com.radiokot.photoprism.features.common

import org.koin.dsl.module
import ua.com.radiokot.photoprism.features.common.logic.DateGroupingUseCase

/**
 * 跨 Feature 共享的通用组件 DI 模块。
 *
 * 注册所有 common/ 目录下的可复用能力。
 */
val CommonFeatureModule = module {

    factory {
        DateGroupingUseCase()
    }
}
