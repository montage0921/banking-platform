import { useEffect, useRef, useState } from "react";
import {
  NavLink,
  Navigate,
  Outlet,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { AdminRoute } from "./auth/AdminRoute";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { ChatWidget } from "./components/ChatWidget";
import { FeatureGuard } from "./components/FeatureGuard";
import { useListCustomerAccounts } from "./hooks/useListCustomerAccounts";
import { AccountDetailPage } from "./pages/AccountDetailPage";
import { AccountListPage } from "./pages/AccountListPage";
import { CustomerCreatePage } from "./pages/CustomerCreatePage";
import { CustomerDetailPage } from "./pages/CustomerDetailPage";
import { CustomerEditPage } from "./pages/CustomerEditPage";
import { CustomerProfilePage } from "./pages/CustomerProfilePage";
import AdminCustomersPage from "./pages/AdminCustomersPage";
import { AccountAdminListPage } from "./pages/AccountAdminListPage";

import { DepositPage } from "./pages/DepositPage";
import { MonthlyStatementPage } from "./pages/MonthlyStatementPage";
import { CreateAccountPage } from "./pages/CreateAccountPage";
import { HomePage } from "./pages/HomePage";
import { LoginPage } from "./pages/LoginPage";
import { PasswordResetPage } from "./pages/PasswordResetPage";
import { RegisterPage } from "./pages/RegisterPage";
import { SpendingInsightsPage } from "./pages/SpendingInsightsPage";
import { StandingOrdersPage } from "./pages/StandingOrdersPage";
import { TransactionHistoryPage } from "./pages/TransactionHistoryPage";
import { TransferPage } from "./pages/TransferPage";
import { WithdrawPage } from "./pages/WithdrawPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { useTheme } from "./theme/ThemeContext";

import voltioIcon from "./images/Voltio_icon.png";
import voltioIconGreen from "./images/Voltio_icon_green.png";
import { AdminRiskScorePage } from "./pages/AdminRiskScorePage";

function getDefaultAuthenticatedRoute(authState) {
  const isAdmin =
    authState.roles.includes("BANK_ADMINISTRATOR") ||
    authState.roles.includes("ROLE_BANK_ADMINISTRATOR");

  if (isAdmin) {
    return "/admin/customers";
  }

  if (authState.customerId) {
    return `/customer/${authState.customerId}`;
  }

  return "/";
}

function PublicOnlyRoute({ children }) {
  const { authState, isAuthenticated } = useAuth();

  if (isAuthenticated) {
    return <Navigate to={getDefaultAuthenticatedRoute(authState)} replace />;
  }

  return children;
}

function ProfileDropdown({ onClose }) {
  const navigate = useNavigate();
  const { logout } = useAuth();

  function handleProfile() {
    onClose();
    navigate("/customer-profile");
  }

  function handleLogout() {
    onClose();
    // Navigate to /login BEFORE clearing auth state so ProtectedRoute never
    // saves the current page as state.from (which would redirect the next user there).
    navigate("/login", { replace: true });
    logout();
  }

  return (
    <div className="navbar-dropdown-menu">
      <button
        type="button"
        className="navbar-dropdown-item"
        onClick={handleProfile}
      >
        Profile
      </button>
      <button
        type="button"
        className="navbar-dropdown-item"
        onClick={handleLogout}
      >
        Log Out
      </button>
    </div>
  );
}

const FEATURE_SEGMENTS = [
  "transactions",
  "statements",
  "insights",
  "standing-orders",
];

function getActiveAccountIdFromPath(pathname) {
  const match = pathname.match(/^\/accounts\/(\d+)/);
  return match ? match[1] : null;
}

function AppLayout() {
  const { authState, isAdmin, isAuthenticated } = useAuth();
  const { isClassic, toggleTheme } = useTheme();
  const customerId = authState.customerId;
  const location = useLocation();
  const navigate = useNavigate();
  // Admin-specific nav logic
  const isAdminUser = isAdmin;
  const isCustomersActive = location.pathname === "/admin/customers";
  const isCustomerAccountsActiveAdmin =
    isAdminUser && location.pathname === "/admin/accounts";
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);
  const dropdownRef = useRef(null);

  // Active account context — seeded from the URL on first render
  const [activeAccountId, setActiveAccountId] = useState(() =>
    getActiveAccountIdFromPath(location.pathname),
  );

  // Account picker modal state
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pendingFeature, setPendingFeature] = useState(null);

  // Accounts list for the picker
  const accountsQuery = useListCustomerAccounts(customerId);

  // Keep activeAccountId in sync when the user navigates via browser back/forward
  useEffect(() => {
    const id = getActiveAccountIdFromPath(location.pathname);
    if (id) {
      setActiveAccountId(id);
    }
  }, [location.pathname]);

  useEffect(() => {
    if (!profileMenuOpen) return;
    function handleOutsideClick(e) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) {
        setProfileMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", handleOutsideClick);
    return () => document.removeEventListener("mousedown", handleOutsideClick);
  }, [profileMenuOpen]);

  const pathname = location.pathname;
  // const overviewPath = isAdmin ? '/customer' : customerId ? `/customer/${customerId}` : '/';
  // For now, Overview always redirects to home
  const overviewPath = "/";

  function handleOverview() {
    setActiveAccountId(null);
    navigate(overviewPath);
  }

  function handleFeatureNav(feature) {
    if (activeAccountId) {
      navigate(`/accounts/${activeAccountId}/${feature}`);
    } else {
      setPendingFeature(feature);
      setPickerOpen(true);
    }
  }

  function handlePickAccount(accountId) {
    const id = String(accountId);
    setActiveAccountId(id);
    setPickerOpen(false);
    navigate(`/accounts/${id}/${pendingFeature}`);
    setPendingFeature(null);
  }

  function handleClosePicker() {
    setPickerOpen(false);
    setPendingFeature(null);
  }

  function isFeatureActive(segment) {
    return pathname.includes(`/${segment}`);
  }

  // Account detail page: /accounts/:id or /accounts/:id/edit (no feature segment)
  const isAccountDetailPage = /^\/accounts\/\d+(\/edit)?$/.test(pathname);

  const isProfilePage = pathname === "/customer-profile";

  const isCustomerAccountsActive = customerId
    ? pathname === `/customer/${customerId}/accounts` || isAccountDetailPage
    : isAccountDetailPage;

  const isOverviewActive =
    !FEATURE_SEGMENTS.some((seg) => isFeatureActive(seg)) &&
    !isCustomerAccountsActive &&
    !isProfilePage &&
    !pathname.startsWith("/accounts/transfer");

  // Show feature buttons when: admin, loading (unknown), has accounts, or already on a feature page
  const hasAccounts = accountsQuery.data
    ? accountsQuery.data.length > 0
    : false;
  const isOnFeaturePage = FEATURE_SEGMENTS.some((seg) => isFeatureActive(seg));
  const showFeatureButtons =
    isAdmin || accountsQuery.isLoading || hasAccounts || isOnFeaturePage;

  return (
    <div className="app-shell">
      <header className="navbar">
        <NavLink className="navbar-brand navbar-brand-link" to="/">
          <img
            src={isClassic ? voltioIconGreen : voltioIcon}
            alt="Voltio"
            className="navbar-logo"
          />
        </NavLink>
        <div className="navbar-actions">
          <button
            type="button"
            className="theme-toggle-btn"
            onClick={toggleTheme}
            aria-label="Toggle theme"
          >
            {isClassic ? "Theme: Classic" : "Theme: New"}
          </button>
          {isAuthenticated ? (
            <div className="navbar-profile" ref={dropdownRef}>
              <button
                type="button"
                className="navbar-avatar-btn"
                aria-label="Open profile menu"
                aria-expanded={profileMenuOpen}
                onClick={() => setProfileMenuOpen((open) => !open)}
              >
                <span className="navbar-avatar-initials">
                  {authState.username
                    ? authState.username[0].toUpperCase()
                    : "U"}
                </span>
              </button>
              {profileMenuOpen && (
                <ProfileDropdown onClose={() => setProfileMenuOpen(false)} />
              )}
            </div>
          ) : (
            <>
              <NavLink className="button-link" to="/login">
                Login
              </NavLink>
              <NavLink className="button-link" to="/register">
                Register
              </NavLink>
            </>
          )}
        </div>
      </header>

      {isAuthenticated && (
        <div className="subnav">
          <nav className="subnav-list">
            {isAdminUser ? (
              <>
                <NavLink
                  className={() =>
                    `subnav-btn${isCustomerAccountsActiveAdmin ? " active" : ""}`
                  }
                  to="/admin/accounts"
                >
                  All Accounts
                </NavLink>
                <NavLink
                  className={() =>
                    `subnav-btn${isCustomersActive ? " active" : ""}`
                  }
                  to="/admin/customers"
                >
                  Customers
                </NavLink>
              </>
            ) : (
              <>
                <button
                  type="button"
                  className={`subnav-btn${isOverviewActive ? " active" : ""}`}
                  onClick={handleOverview}
                >
                  Overview
                </button>
                {customerId && (
                  <NavLink
                    className={() =>
                      `subnav-btn${isCustomerAccountsActive ? " active" : ""}`
                    }
                    to={`/customer/${customerId}/accounts`}
                  >
                    My Accounts
                  </NavLink>
                )}
                {showFeatureButtons && (
                  <NavLink
                    className={() =>
                      `subnav-btn${pathname.startsWith("/accounts/transfer") ? " active" : ""}`
                    }
                    to="/accounts/transfer"
                  >
                    Transfer Funds
                  </NavLink>
                )}
                {showFeatureButtons && (
                  <>
                    <button
                      type="button"
                      className={`subnav-btn${isFeatureActive("transactions") ? " active" : ""}`}
                      onClick={() => handleFeatureNav("transactions")}
                    >
                      Transactions
                    </button>
                    <button
                      type="button"
                      className={`subnav-btn${isFeatureActive("statements") ? " active" : ""}`}
                      onClick={() => handleFeatureNav("statements")}
                    >
                      Monthly Statement
                    </button>
                    <button
                      type="button"
                      className={`subnav-btn${isFeatureActive("insights") ? " active" : ""}`}
                      onClick={() => handleFeatureNav("insights")}
                    >
                      Spending Insights
                    </button>
                    <button
                      type="button"
                      className={`subnav-btn${isFeatureActive("standing-orders") ? " active" : ""}`}
                      onClick={() => handleFeatureNav("standing-orders")}
                    >
                      Standing Orders
                    </button>
                  </>
                )}
              </>
            )}
          </nav>
        </div>
      )}

      {pickerOpen && (
        <div className="modal-backdrop" onClick={handleClosePicker}>
          <div
            className="modal-panel stack"
            role="dialog"
            aria-modal="true"
            aria-labelledby="account-picker-title"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="section-header">
              <div>
                <h3 id="account-picker-title">Select an Account</h3>
                <p className="muted text-top-muted">
                  Choose which account to view{" "}
                  {pendingFeature && pendingFeature.replace("-", " ")}.
                </p>
              </div>
              <button
                type="button"
                className="secondary"
                onClick={handleClosePicker}
              >
                Close
              </button>
            </div>
            {accountsQuery.isLoading ? (
              <div className="banner success">Loading accounts…</div>
            ) : accountsQuery.data && accountsQuery.data.length > 0 ? (
              <ul className="account-picker-list">
                {accountsQuery.data.map((account) => (
                  <li key={account.accountId}>
                    <button
                      type="button"
                      className="account-picker-item"
                      onClick={() => handlePickAccount(account.accountId)}
                    >
                      <span className="account-picker-id">
                        #{account.accountId}
                      </span>
                      <span className="account-picker-meta">
                        {account.accountType} · {account.balance}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">
                No accounts found. Please create an account first.
              </p>
            )}
          </div>
        </div>
      )}

      <main className="content-area">
        <Outlet />
      </main>

      {isAuthenticated && !isAdmin && customerId && <ChatWidget />}
    </div>
  );
}

export default function App() {
  const { authState } = useAuth();
  const defaultAuthenticatedRoute = getDefaultAuthenticatedRoute(authState);

  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<HomePage />} />
        <Route
          path="/login"
          element={
            <PublicOnlyRoute>
              <LoginPage />
            </PublicOnlyRoute>
          }
        />
        <Route
          path="/register"
          element={
            <PublicOnlyRoute>
              <RegisterPage />
            </PublicOnlyRoute>
          }
        />
        <Route
          path="/password-reset"
          element={
            <PublicOnlyRoute>
              <PasswordResetPage />
            </PublicOnlyRoute>
          }
        />

        <Route element={<ProtectedRoute />}>
          <Route element={<AdminRoute />}>
            <Route path="/admin/customers" element={<AdminCustomersPage />} />
            <Route path="/admin/accounts" element={<AccountAdminListPage />} />
            <Route
              path="/admin/:customerId/risk-assessment"
              element={<AdminRiskScorePage />}
            />
          </Route>
          <Route path="/customer/create" element={<CustomerCreatePage />} />
          <Route
            path="/customer/:customerId"
            element={<CustomerDetailPage />}
          />
          <Route
            path="/customer/:customerId/edit"
            element={<CustomerEditPage />}
          />
          <Route path="/customer-profile" element={<CustomerProfilePage />} />
          <Route
            path="/customer/:customerId/accounts"
            element={<AccountListPage />}
          />
          <Route
            path="/customer/:customerId/accounts/create"
            element={<CreateAccountPage />}
          />
          <Route path="/accounts/:accountId" element={<AccountDetailPage />} />
          <Route
            path="/accounts/:accountId/edit"
            element={<AccountDetailPage />}
          />
          <Route
            path="/accounts/:accountId/deposit"
            element={<DepositPage />}
          />
          <Route
            path="/accounts/:accountId/withdraw"
            element={<WithdrawPage />}
          />
          <Route element={<FeatureGuard />}>
            <Route
              path="/accounts/:accountId/transactions"
              element={<TransactionHistoryPage />}
            />
            <Route
              path="/accounts/:accountId/standing-orders"
              element={<StandingOrdersPage />}
            />
            <Route
              path="/accounts/:accountId/statements"
              element={<MonthlyStatementPage />}
            />
            <Route
              path="/accounts/:accountId/insights"
              element={<SpendingInsightsPage />}
            />
          </Route>
          <Route path="/accounts/transfer" element={<TransferPage />} />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
