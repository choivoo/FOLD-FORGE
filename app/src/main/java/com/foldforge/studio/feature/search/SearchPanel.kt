package com.foldforge.studio.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.feature.workspace.WorkspaceViewModel

/** Global project search (incremental index) and Find References. */
@Composable
fun SearchPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf(vm.wordAtCursor() ?: "") }
    var caseSensitive by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("Search")
        TabRow(tab, containerColor = Forge.colors.panelAlt) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Search") })
            Tab(tab == 1, { tab = 1 }, text = { Text("References") })
        }
        if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (tab == 0) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, label = { Text("Search in project") },
                modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("global_search"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.globalSearch(query, caseSensitive, regex, word) }),
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                FilterChip(caseSensitive, { caseSensitive = !caseSensitive }, label = { Text("Aa") }); Spacer(Modifier.width(6.dp))
                FilterChip(word, { word = !word }, label = { Text("Word") }); Spacer(Modifier.width(6.dp))
                FilterChip(regex, { regex = !regex }, label = { Text(".*") }); Spacer(Modifier.width(6.dp))
                FilterChip(false, { vm.globalSearch(query, caseSensitive, regex, word) }, label = { Text("Search") })
            }
            val grouped = vm.searchResults.groupBy { it.path }
            Text("${vm.searchResults.size} results in ${grouped.size} files", fontSize = 12.sp, color = Forge.colors.muted, modifier = Modifier.padding(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (path, results) ->
                    item(key = "h:$path") { Text(path, fontSize = 12.sp, color = Forge.colors.accent2, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                    items(results, key = { "${it.path}:${it.line}:${it.column}" }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { vm.openFile(r.path, r.line) }.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text("${r.line}", fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.muted, modifier = Modifier.width(40.dp))
                            Text(r.lineText.trim(), fontFamily = CodeFont, fontSize = 12.sp, color = Forge.colors.text, maxLines = 2)
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                symbol, { symbol = it }, singleLine = true, label = { Text("Symbol (function, class, variable)") },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.findReferences(symbol) }),
            )
            FilterChip(false, { vm.findReferences(symbol) }, label = { Text("Find references") }, modifier = Modifier.padding(horizontal = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.referenceResults, key = { "${it.path}:${it.line}:${it.column}" }) { r ->
                    Row(Modifier.fillMaxWidth().clickable { vm.openFile(r.path, r.line) }.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(if (r.isDefinition) "DEF" else "REF", fontSize = 10.sp, color = if (r.isDefinition) Forge.colors.accent else Forge.colors.muted, modifier = Modifier.width(34.dp))
                        Column {
                            Text("${r.path}:${r.line}", fontSize = 11.sp, color = Forge.colors.accent2)
                            Text(r.lineText.trim(), fontFamily = CodeFont, fontSize = 12.sp, color = Forge.colors.text, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
