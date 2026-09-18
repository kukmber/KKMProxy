package io.github.romanvht.byedpi.activities

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.adapters.DomainListAdapter
import io.github.romanvht.byedpi.data.DomainList
import io.github.romanvht.byedpi.utility.ClipboardUtils
import io.github.romanvht.byedpi.utility.DomainListUtils

/** Списки доменов для подбора стратегии — в стиле KKMProxy. */
class DomainListsActivity : BaseActivity() {

    override val useDynamicColors = false

    private lateinit var adapter: DomainListAdapter
    private lateinit var total: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_domain_lists)

        val topBar = DashUi.setupTopBar(this, getString(R.string.lists_title))
        topBar.addAction(R.drawable.ic_k_refresh, getString(R.string.lists_reset)) { confirmReset() }

        total = findViewById(R.id.listsTotal)
        DomainListUtils.syncLists(this)

        adapter = DomainListAdapter(
            onStateChanged = { list ->
                DomainListUtils.toggleListActive(this, list.id)
                refresh()
            },
            onEdit = ::showEditDialog,
            onDelete = { list ->
                DomainListUtils.deleteList(this, list.id)
                refresh()
            },
            onCopy = { list ->
                ClipboardUtils.copy(this, list.domains.joinToString("\n"), list.name)
            },
            itemLayout = R.layout.item_dash_domain_list,
            dialogTheme = 0,
        )
        findViewById<RecyclerView>(R.id.listsRecycler).apply {
            layoutManager = LinearLayoutManager(this@DomainListsActivity)
            adapter = this@DomainListsActivity.adapter
            itemAnimator = null
        }
        findViewById<android.view.View>(R.id.listsAddButton).setOnClickListener { showEditDialog(null) }
        refresh()
    }

    private fun refresh() {
        adapter.submitList(DomainListUtils.getLists(this).sortedBy { it.name.lowercase() })
        total.text = getString(R.string.lists_active_total, DomainListUtils.getActiveDomains(this).size)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lists_reset)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DomainListUtils.resetLists(this)
                Toast.makeText(this, R.string.domain_list_reset_done, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    /** list == null — создание нового списка */
    private fun showEditDialog(list: DomainList?) {
        DashUi.inputDialog(
            activity = this,
            title = getString(if (list == null) R.string.domain_list_add_new else R.string.domain_list_edit),
            firstHint = getString(R.string.domain_list_name_hint),
            firstValue = list?.name,
            secondHint = getString(R.string.domain_list_domains_hint),
            secondValue = list?.domains?.joinToString("\n"),
        ) { nameInput, domainsInput ->
            val name = nameInput.trim()
            val domains = domainsInput.lines().map { it.trim() }.filter { it.isNotEmpty() }
            when {
                name.isEmpty() -> toast(R.string.domain_list_name_empty)
                domains.isEmpty() -> toast(R.string.domain_list_domains_empty)
                list == null -> toast(
                    if (DomainListUtils.addList(this, name, domains)) R.string.domain_list_added
                    else R.string.domain_list_already_exists,
                )
                else -> toast(
                    if (DomainListUtils.updateList(this, list.id, name, domains)) R.string.domain_list_updated
                    else R.string.domain_list_update_failed,
                )
            }
            refresh()
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
