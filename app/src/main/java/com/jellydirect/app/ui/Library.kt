package com.jellydirect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.ItemQuery
import com.jellydirect.app.data.JellyApi
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer

/** Lets a screen hand a query to the generic list screen without encoding it into the route. */
object ListSpecs {
    class Spec(val title: String, val query: ItemQuery)

    private val map = HashMap<String, Spec>()
    fun put(title: String, q: ItemQuery): String = UUID.randomUUID().toString().also { map[it] = Spec(title, q) }
    fun get(key: String): Spec? = map[key]
}

/** Libraries index: one wide tile per library. */
@Composable
fun LibrariesScreen(api: JellyApi, onOpenLibrary: (Item) -> Unit) {
    val loader = rememberLoad(api, "views", ListSerializer(Item.serializer())) { api.views() }
    loader.Show { views ->
        val tv = LocalIsTv.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (tv) 300.dp else 220.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Libraries", Modifier.padding(0.dp)) }
            items(views, key = { it.id }) { PosterCard(it, api, wide = true, onClick = onOpenLibrary) }
        }
    }
}

private fun typesFor(collectionType: String?): String? = when (collectionType) {
    "movies" -> "Movie"
    "tvshows" -> "Series"
    "boxsets" -> "BoxSet"
    else -> null
}

private enum class Tab(val label: String) { ALL("All"), GENRES("Genres"), COLLECTIONS("Collections"), STUDIOS("Studios"), FAVORITES("Favorites") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(api: JellyApi, view: Item, onOpen: (Item) -> Unit, onOpenList: (String) -> Unit) {
    val types = typesFor(view.collectionType)
    val mixed = types == null
    val tabs = remember(view.id) {
        buildList {
            add(Tab.ALL)
            if (!mixed) add(Tab.GENRES)
            if (view.collectionType == "movies") add(Tab.COLLECTIONS)
            if (view.collectionType == "movies" || view.collectionType == "tvshows") add(Tab.STUDIOS)
            add(Tab.FAVORITES)
        }
    }
    var tab by rememberSaveable(view.id) { mutableStateOf(Tab.ALL) }

    val header: @Composable () -> Unit = {
        Box(Modifier.statusBarsPadding()) {
            Text(view.name, style = Type.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
    val chips: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { t -> Chip(t.label, tab == t) { tab = t } }
        }
    }

    when (tab) {
        Tab.ALL -> PagedGrid(
            api, ItemQuery(parentId = view.id, types = types, recursive = !mixed), onOpen,
            header = { header(); chips() },
        )
        Tab.FAVORITES -> PagedGrid(
            api, ItemQuery(parentId = view.id, types = types ?: "Movie,Series,Episode,Video", recursive = true, filters = "IsFavorite"), onOpen,
            header = { header(); chips() },
        )
        Tab.COLLECTIONS -> PagedGrid(
            api, ItemQuery(types = "BoxSet", recursive = true), onOpen,
            header = { header(); chips() },
        )
        Tab.GENRES, Tab.STUDIOS -> {
            val isGenre = tab == Tab.GENRES
            val loader = rememberLoad(api, "facet:${view.id}:$tab") {
                if (isGenre) api.genres(view.id, types) else api.studios(view.id, types)
            }
            Column2(header, chips) {
                loader.Show { facets ->
                    FlowRow(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        facets.forEach { f ->
                            Chip(f.name, false) {
                                val q = ItemQuery(
                                    parentId = view.id, types = types, recursive = true,
                                    genre = if (isGenre) f.name else null,
                                    studioId = if (!isGenre) f.id else null,
                                )
                                onOpenList(ListSpecs.put(f.name, q))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Column2(header: @Composable () -> Unit, chips: @Composable () -> Unit, body: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        header()
        chips()
        body()
    }
}

/** Generic list screen driven by a [ListSpecs] entry (genre/studio results, etc.). */
@Composable
fun ListScreen(api: JellyApi, key: String, onOpen: (Item) -> Unit) {
    val spec = ListSpecs.get(key)
    if (spec == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Nothing to show", color = Color.Gray) }
        return
    }
    PagedGrid(api, spec.query, onOpen, header = {
        Box(Modifier.statusBarsPadding()) {
            Text(spec.title, style = Type.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
        }
    })
}

/** Endless poster grid: loads the next page when the user nears the end. */
@Composable
fun PagedGrid(
    api: JellyApi,
    q: ItemQuery,
    onOpen: (Item) -> Unit,
    header: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val tv = LocalIsTv.current
    var items by remember(q) { mutableStateOf(listOf<Item>()) }
    var total by remember(q) { mutableIntStateOf(-1) }
    var loading by remember(q) { mutableStateOf(false) }
    var error by remember(q) { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(q)
    val menu = remember { mutableStateOf<Item?>(null) }
    val state = rememberLazyGridState()

    fun more() {
        if (loading) return
        loading = true
        val asked = q
        val startAt = items.size
        scope.launch {
            try {
                val r = api.items(asked.copy(start = startAt))
                if (current == asked) {
                    items = items + r.items
                    total = r.total
                    error = null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current == asked) error = e.message ?: "Error"
            }
            loading = false
        }
    }

    LaunchedEffect(q) { more() }
    val nearEnd by remember(q) {
        derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - 12 }
    }
    LaunchedEffect(nearEnd, items.size) {
        if (nearEnd && error == null && (total < 0 || items.size < total) && items.isNotEmpty()) more()
    }

    LazyVerticalGrid(
        columns = if (tv) GridCells.Adaptive(170.dp) else GridCells.Fixed(3),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { androidx.compose.foundation.layout.Column { header() } }
        items(items, key = { it.id }) { PosterCard(it, api, modifier = Modifier.animateItem(), fill = true, onLong = { m -> menu.value = m }, onClick = onOpen) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                when {
                    error != null -> Text(error!!, color = Color.Gray)
                    loading -> CircularProgressIndicator(color = Accent)
                    total == 0 -> Text("Nothing here yet", color = Color.Gray)
                }
            }
        }
    }
    ItemMenu(menu, api, onOpen = onOpen, onChanged = {
        // Re-fetch what is on screen so watched/favorite badges update.
        val n = items.size
        scope.launch {
            try {
                val r = api.items(q.copy(start = 0, limit = maxOf(n, q.limit)))
                items = r.items
                total = r.total
            } catch (_: Exception) {
            }
        }
    })
}
