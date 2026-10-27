package id.jerco.billingroyal.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.ui.screens.AccountScreen
import id.jerco.billingroyal.ui.screens.CustomerDetailScreen
import id.jerco.billingroyal.ui.screens.CustomerFormScreen
import id.jerco.billingroyal.ui.screens.CustomerListScreen
import id.jerco.billingroyal.ui.screens.ExpenseFormScreen
import id.jerco.billingroyal.ui.screens.ExpenseListScreen
import id.jerco.billingroyal.ui.screens.HomeScreen
import id.jerco.billingroyal.ui.screens.InvoiceCreateScreen
import id.jerco.billingroyal.ui.screens.InvoiceDetailScreen
import id.jerco.billingroyal.ui.screens.InvoiceListScreen
import id.jerco.billingroyal.ui.screens.LoginScreen
import id.jerco.billingroyal.ui.screens.ReportScreen
import id.jerco.billingroyal.ui.screens.SplashScreen

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
    const val CUSTOMERS = "customers"
    const val CUSTOMER = "customer/{id}"
    const val CUSTOMER_FORM = "customerForm?id={id}"
    const val INVOICE_CREATE = "invoiceCreate"
    const val INVOICES = "invoices"
    const val INVOICE = "invoice/{id}"
    const val EXPENSES = "expenses"
    const val EXPENSE_FORM = "expenseForm?id={id}"
    const val REPORT = "report"
    const val ACCOUNT = "account"

    fun customer(id: Long) = "customer/$id"
    fun customerForm(id: Long? = null) = "customerForm?id=${id ?: -1L}"
    fun invoice(id: Long) = "invoice/$id"
    fun expenseForm(id: Long? = null) = "expenseForm?id=${id ?: -1L}"
}

private fun NavHostController.goToLogin() {
    navigate(Routes.LOGIN) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

private fun NavHostController.goToHome() {
    navigate(Routes.HOME) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
fun AppNavHost(sessionManager: SessionManager) {
    val nav = rememberNavController()

    // 401 / 403 account_inactive: token, Room, dan DataStore sudah dihapus; kembali ke Login.
    LaunchedEffect(Unit) {
        sessionManager.events.collect { nav.goToLogin() }
    }

    val idArg = listOf(navArgument("id") { type = NavType.LongType })
    val optionalIdArg = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L })

    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(onHome = { nav.goToHome() }, onLogin = { nav.goToLogin() })
        }
        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = { nav.goToHome() })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onCreateInvoice = { nav.navigate(Routes.INVOICE_CREATE) },
                onCustomers = { nav.navigate(Routes.CUSTOMERS) },
                onExpenses = { nav.navigate(Routes.EXPENSES) },
                onInvoices = { nav.navigate(Routes.INVOICES) },
                onReport = { nav.navigate(Routes.REPORT) },
                onAccount = { nav.navigate(Routes.ACCOUNT) },
            )
        }
        composable(Routes.CUSTOMERS) {
            CustomerListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.customer(it)) },
                onAdd = { nav.navigate(Routes.customerForm()) },
            )
        }
        composable(Routes.CUSTOMER, arguments = idArg) {
            CustomerDetailScreen(
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.customerForm(it)) },
            )
        }
        composable(Routes.CUSTOMER_FORM, arguments = optionalIdArg) {
            CustomerFormScreen(
                onBack = { nav.popBackStack() },
                onCheckList = { nav.navigate(Routes.CUSTOMERS) { launchSingleTop = true } },
            )
        }
        composable(Routes.INVOICE_CREATE) {
            InvoiceCreateScreen(
                onBack = { nav.popBackStack() },
                onCheckList = { nav.navigate(Routes.INVOICES) { launchSingleTop = true } },
                onCreated = { id ->
                    nav.navigate(Routes.invoice(id)) { popUpTo(Routes.INVOICE_CREATE) { inclusive = true } }
                },
            )
        }
        composable(Routes.INVOICES) {
            InvoiceListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.invoice(it)) },
                // Bila layar Buat Invoice masih ada di bawah (setelah "Periksa invoice"), kembali ke sana agar draft tidak terduplikasi.
                onCreate = { if (!nav.popBackStack(Routes.INVOICE_CREATE, inclusive = false)) nav.navigate(Routes.INVOICE_CREATE) },
            )
        }
        composable(Routes.INVOICE, arguments = idArg) {
            InvoiceDetailScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.EXPENSES) {
            ExpenseListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.expenseForm(it)) },
                onAdd = { nav.navigate(Routes.expenseForm()) },
            )
        }
        composable(Routes.EXPENSE_FORM, arguments = optionalIdArg) {
            ExpenseFormScreen(
                onBack = { nav.popBackStack() },
                onCheckList = { nav.navigate(Routes.EXPENSES) { launchSingleTop = true } },
            )
        }
        composable(Routes.REPORT) {
            ReportScreen(onBack = { nav.popBackStack() }, onOpenInvoice = { nav.navigate(Routes.invoice(it)) })
        }
        composable(Routes.ACCOUNT) {
            AccountScreen(onBack = { nav.popBackStack() }, onLoggedOut = { nav.goToLogin() })
        }
    }
}
