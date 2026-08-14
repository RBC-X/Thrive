package com.thrive.backup

import com.thrive.app.data.model.BudgetState
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.ShoppingItem
import com.thrive.app.data.remote.BackupMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupMergeTest {

    private fun pantryItem(id: String, name: String = id, quantity: Int = 1) =
        PantryItem(id = id, name = name, category = "Grocery", location = "Pantry", quantity = quantity)

    private fun shopItem(id: String, name: String = id, price: Double = 1.0, checked: Boolean = false) =
        ShoppingItem(id = id, name = name, category = "Grocery", quantity = 1, unit = "", estPrice = price, checked = checked)

    // ---- Favorites ----

    @Test
    fun favoritesMergeIsUnion() {
        assertEquals(
            setOf("a", "b", "c"),
            BackupMerge.favorites(setOf("a", "b"), setOf("b", "c")),
        )
        assertEquals(emptySet<String>(), BackupMerge.favorites(emptySet(), emptySet()))
    }

    // ---- Pantry ----

    @Test
    fun pantryMergeKeepsLocalVersionsAndAppendsRemoteOnly() {
        val local = listOf(pantryItem("1", name = "local edit", quantity = 3), pantryItem("2"))
        val remote = listOf(pantryItem("1", name = "remote edit", quantity = 9), pantryItem("3"))
        val merged = BackupMerge.pantry(local, remote)

        assertEquals(3, merged.size)
        // Same id "1" keeps the local version — remote edits never clobber.
        assertEquals("local edit", merged[0].name)
        assertEquals(3, merged[0].quantity)
        // Remote-only items are appended.
        assertEquals("3", merged[2].id)
    }

    @Test
    fun pantryMergeWithEmptyLocalAdoptsRemote() {
        val remote = listOf(pantryItem("a"), pantryItem("b"))
        val merged = BackupMerge.pantry(emptyList(), remote)
        assertEquals(remote, merged)
    }

    // ---- Budget ----

    @Test
    fun budgetMergeKeepsLocalAmountWhenSet() {
        val local = BudgetState(budget = 100.0, people = 2, items = listOf(shopItem("s1")))
        val remote = BudgetState(budget = 40.0, people = 4, items = listOf(shopItem("s2")))
        val merged = BackupMerge.budget(local, remote)

        assertEquals(100.0, merged.budget, 0.001) // local set → wins
        assertEquals(2, merged.people)            // local set → wins
        assertEquals(listOf("s1", "s2"), merged.items.map { it.id }) // union
    }

    @Test
    fun budgetMergeAdoptsRemoteWhenLocalUnset() {
        val local = BudgetState() // budget 0, people 1 → unset
        val remote = BudgetState(budget = 55.0, people = 3, items = listOf(shopItem("s9")))
        val merged = BackupMerge.budget(local, remote)

        assertEquals(55.0, merged.budget, 0.001)
        assertEquals(3, merged.people)
        assertEquals(listOf("s9"), merged.items.map { it.id })
    }

    @Test
    fun budgetMergeItemConflictKeepsLocalVersion() {
        val local = BudgetState(budget = 10.0, people = 1, items = listOf(shopItem("s1", checked = true)))
        val remote = BudgetState(budget = 0.0, people = 1, items = listOf(shopItem("s1", checked = false), shopItem("s2")))
        val merged = BackupMerge.budget(local, remote)

        assertEquals(1, merged.items.count { it.id == "s1" })
        assert(merged.items.first { it.id == "s1" }.checked) // local version survives
        assertEquals(2, merged.items.size)
    }

    @Test
    fun budgetMergeWithNullRemoteReturnsLocal() {
        val local = BudgetState(budget = 20.0, people = 2, items = listOf(shopItem("x")))
        assertEquals(local, BackupMerge.budget(local, null))
    }
}
