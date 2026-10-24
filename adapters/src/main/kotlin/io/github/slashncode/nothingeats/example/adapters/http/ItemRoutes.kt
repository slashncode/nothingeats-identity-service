package io.github.slashncode.nothingeats.example.adapters.http

import io.github.slashncode.nothingeats.example.application.CreateItem
import io.github.slashncode.nothingeats.example.application.GetItem
import io.github.slashncode.nothingeats.example.domain.Item
import io.github.slashncode.nothingeats.example.domain.ItemId
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.authenticated
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.auth.user
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class CreateItemRequest(
    val name: String,
)

@Serializable
data class ItemResponse(
    val id: String,
    val name: String,
    val createdAt: String,
)

private fun Item.toResponse() = ItemResponse(id.toString(), name.value, createdAt.toString())

class ItemRoutes(
    private val createItem: CreateItem,
    private val getItem: GetItem,
) : HttpRoutes {
    override fun Route.register() {
        route("/api/example/v1/items") {
            authenticated {
                post {
                    val item = createItem(call.receive<CreateItemRequest>().name, createdBy = call.user.id)
                    call.response.header(HttpHeaders.Location, "/api/example/v1/items/${item.id}")
                    call.respond(HttpStatusCode.Created, item.toResponse())
                }
            }
            public {
                get("/{id}") {
                    val raw = call.parameters["id"].orEmpty()
                    val id = parseId(raw) ?: throw AppError.NotFound(ErrorCode("ITEM_NOT_FOUND"), "Item $raw does not exist")
                    call.respond(getItem(id).toResponse())
                }
            }
        }
    }

    private fun parseId(raw: String): ItemId? = Uuid.parseOrNull(raw)?.let(::ItemId)
}
