package ai.kira.mobile.application.routing

import ai.kira.mobile.domain.model.ProviderRoute
import ai.kira.mobile.domain.policy.FreeModelPolicy

object FreeRouteSelector {
    fun select(routes: List<ProviderRoute>): List<ProviderRoute> =
        routes.asSequence()
            .filter { route ->
                route.enabled &&
                    route.hasKey &&
                    FreeModelPolicy.isVerifiedFreeChatModel(route.providerId, route.modelId)
            }
            .sortedWith(compareBy<ProviderRoute> { it.priority }.thenBy { it.providerId })
            .toList()
}
