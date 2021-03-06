package kotlinserverless.framework.dispatchers

import kotlinserverless.framework.models.*
import java.io.File
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import framework.services.DaoService
import kotlinserverless.framework.services.SOAResult
import kotlinserverless.framework.services.SOAResultType
import main.daos.*
import main.helpers.ControllerHelper
import main.helpers.JsonHelper
import main.services.user_account.GetUserAccountService
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.mockito.internal.matchers.Not
import java.lang.Exception
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.full.createInstance

/**
 * Request Dispatcher implementation
 */
open class RequestDispatcher: Dispatcher<ApiGatewayRequest, Any> {
    @Throws(RouterException::class, NotFoundException::class)
    override fun locate(request: ApiGatewayRequest): Any? {
        val path = request.input["path"]
        for ((regex, inputModel, outputModel, controller, shouldValidatePost, shouldValidatePut, shouldValidateGet) in ROUTER.routes) {
            if (!Regex(regex).matches(path as CharSequence)) {
                continue
            }

            val outputModelClass = Class.forName(outputModel).kotlin
            val controllerClass = Class.forName(controller).kotlin
            val controllerInstance = controllerClass.createInstance()

            val func = controllerClass.members.find { it.name == "defaultRouting" }
            val requestData = ControllerHelper.getRequestData(request)
            val user = findUserByRequest(requestData)
            val method = (requestData.request.input[ControllerHelper.HTTP_METHOD] as String).toLowerCase()

            val result = try {
                val executionResult = DaoService.execute {
                    func?.call(
                            controllerInstance,
                            inputModel,
                            outputModelClass::class.java,
                            requestData,
                            user,
                            controllerInstance,
                            method,
                            shouldValidatePost,
                            shouldValidatePut,
                            shouldValidateGet
                    ) as SOAResult<Any>
                }
                DaoService.throwOrReturn(executionResult)
                executionResult.data!!
            }
            catch(e: InvocationTargetException) {
                Handler.log(e, e.message)
                throw e.targetException
            }
            catch(e: KotlinNullPointerException) {
                Handler.log(e, e.message)
                throw NotFoundException()
            }

            if(result.result == SOAResultType.SUCCESS) {
                return result.data
            } else {
                println("There was an error processing the request.")
                println(result.message)
                throw InternalError(result.message)
            }
        }

        throw RouterException(path as? String ?: "")
    }

    private fun findUserByRequest(requestData: ControllerHelper.RequestData) : UserAccount? {
        return GetUserAccountService.execute(
            requestData.queryParams["userId"]?.toString()?.toInt(),
            requestData.queryParams["email"]?.toString(),
            requestData.userAuth?.apiKey
        ).data
    }

    /**
     * Singleton that loads the routes once and keep them on memory
     */
    companion object BackendRouter {
        private val FILE = File("routes.yml")
        val ROUTER: Routes = ObjectMapper(YAMLFactory()).readValue(FILE, Routes::class.java)
    }
}