/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.frontend.di

import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.container.StorageComponentContainer
import org.jetbrains.kotlin.container.useImpl
import org.jetbrains.kotlin.container.useInstance
import org.jetbrains.kotlin.context.ModuleContext
import org.jetbrains.kotlin.contracts.ContractDeserializerImpl
import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.platform.TargetPlatformVersion
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.calls.components.ClassicTypeSystemContextForCS
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactoryImpl
import org.jetbrains.kotlin.resolve.calls.tower.KotlinResolutionStatelessCallbacksImpl
import org.jetbrains.kotlin.resolve.checkers.ExperimentalUsageChecker
import org.jetbrains.kotlin.resolve.lazy.*
import org.jetbrains.kotlin.resolve.lazy.declarations.DeclarationProviderFactory
import org.jetbrains.kotlin.types.SubstitutingScopeProviderImpl
import org.jetbrains.kotlin.types.expressions.DeclarationScopeProviderForLocalClassifierAnalyzer
import org.jetbrains.kotlin.types.expressions.LocalClassDescriptorHolder
import org.jetbrains.kotlin.types.expressions.LocalLazyDeclarationResolver

fun StorageComponentContainer.configureModule(
    moduleContext: ModuleContext,
    platform: TargetPlatform,
    analyzerServices: PlatformDependentAnalyzerServices,
    trace: BindingTrace,
    languageVersionSettings: LanguageVersionSettings
) {
    useInstance(moduleContext)
    useInstance(moduleContext.module)
    useInstance(moduleContext.project)
    useInstance(moduleContext.storageManager)
    useInstance(moduleContext.module.builtIns)
    useInstance(trace)
    useInstance(languageVersionSettings)

    useInstance(platform)
    useInstance(analyzerServices)
    useInstance(platform.componentPlatforms.singleOrNull()?.targetPlatformVersion ?: TargetPlatformVersion.NoVersion)

    analyzerServices.platformConfigurator.configureModuleComponents(this)
    analyzerServices.platformConfigurator.configureModuleDependentCheckers(this)

    for (extension in StorageComponentContainerContributor.getInstances(moduleContext.project)) {
        extension.registerModuleComponents(this, platform, moduleContext.module)
    }

    configurePlatformIndependentComponents()
}

private fun StorageComponentContainer.configurePlatformIndependentComponents() {
    useImpl<SupertypeLoopCheckerImpl>()
    useImpl<KotlinResolutionStatelessCallbacksImpl>()
    useImpl<DataFlowValueFactoryImpl>()

    useImpl<ExperimentalUsageChecker>()
    useImpl<ExperimentalUsageChecker.Overrides>()
    useImpl<ExperimentalUsageChecker.ClassifierUsage>()

    useImpl<ContractDeserializerImpl>()
    useImpl<CompilerDeserializationConfiguration>()

    useImpl<ClassicTypeSystemContextForCS>()
}

/**
 * Actually, those should be present in 'configurePlatformIndependentComponents',
 * but, unfortunately, this is currently impossible, because in some lightweight
 * containers (see [createContainerForBodyResolve] and similar) some dependencies
 * are missing
 *
 * If you're not doing some trickery with containers, you should use them.
 */
fun StorageComponentContainer.configureStandardResolveComponents() {
    useImpl<ResolveSession>()
    useImpl<LazyTopDownAnalyzer>()
    useImpl<AnnotationResolverImpl>()
}

fun StorageComponentContainer.configureIncrementalCompilation(lookupTracker: LookupTracker, expectActualTracker: ExpectActualTracker) {
    useInstance(lookupTracker)
    useInstance(expectActualTracker)
}

fun createContainerForBodyResolve(
    moduleContext: ModuleContext,
    bindingTrace: BindingTrace,
    platform: TargetPlatform,
    statementFilter: StatementFilter,
    analyzerServices: PlatformDependentAnalyzerServices,
    languageVersionSettings: LanguageVersionSettings
): StorageComponentContainer = createContainer("BodyResolve", analyzerServices) {
    configureModule(moduleContext, platform, analyzerServices, bindingTrace, languageVersionSettings)

    useInstance(statementFilter)

    useInstance(BodyResolveCache.ThrowException)
    useImpl<AnnotationResolverImpl>()

    useImpl<BodyResolver>()
    useImpl<SubstitutingScopeProviderImpl>()
}

fun createContainerForLazyBodyResolve(
    moduleContext: ModuleContext,
    kotlinCodeAnalyzer: KotlinCodeAnalyzer,
    bindingTrace: BindingTrace,
    platform: TargetPlatform,
    bodyResolveCache: BodyResolveCache,
    analyzerServices: PlatformDependentAnalyzerServices,
    languageVersionSettings: LanguageVersionSettings
): StorageComponentContainer = createContainer("LazyBodyResolve", analyzerServices) {
    configureModule(moduleContext, platform, analyzerServices, bindingTrace, languageVersionSettings)

    useInstance(kotlinCodeAnalyzer)
    useInstance(kotlinCodeAnalyzer.fileScopeProvider)
    useInstance(bodyResolveCache)
    useImpl<AnnotationResolverImpl>()
    useImpl<LazyTopDownAnalyzer>()
    useImpl<BasicAbsentDescriptorHandler>()
    useImpl<SubstitutingScopeProviderImpl>()
}

fun createContainerForLazyLocalClassifierAnalyzer(
    moduleContext: ModuleContext,
    bindingTrace: BindingTrace,
    platform: TargetPlatform,
    lookupTracker: LookupTracker,
    languageVersionSettings: LanguageVersionSettings,
    statementFilter: StatementFilter,
    localClassDescriptorHolder: LocalClassDescriptorHolder,
    analyzerServices: PlatformDependentAnalyzerServices
): StorageComponentContainer = createContainer("LocalClassifierAnalyzer", analyzerServices) {
    configureModule(moduleContext, platform, analyzerServices, bindingTrace, languageVersionSettings)

    useInstance(localClassDescriptorHolder)
    useInstance(lookupTracker)
    useInstance(ExpectActualTracker.DoNothing)

    useImpl<LazyTopDownAnalyzer>()

    useInstance(NoTopLevelDescriptorProvider)

    CompilerEnvironment.configure(this)

    useInstance(FileScopeProvider.ThrowException)
    useImpl<AnnotationResolverImpl>()

    useImpl<DeclarationScopeProviderForLocalClassifierAnalyzer>()
    useImpl<LocalLazyDeclarationResolver>()

    useImpl<SubstitutingScopeProviderImpl>()
    useInstance(statementFilter)
}

fun createContainerForLazyResolve(
    moduleContext: ModuleContext,
    declarationProviderFactory: DeclarationProviderFactory,
    bindingTrace: BindingTrace,
    platform: TargetPlatform,
    analyzerServices: PlatformDependentAnalyzerServices,
    targetEnvironment: TargetEnvironment,
    languageVersionSettings: LanguageVersionSettings
): StorageComponentContainer = createContainer("LazyResolve", analyzerServices) {
    configureModule(moduleContext, platform, analyzerServices, bindingTrace, languageVersionSettings)

    configureStandardResolveComponents()

    useInstance(declarationProviderFactory)
    useImpl<SubstitutingScopeProviderImpl>()

    targetEnvironment.configure(this)

}
