import StoreKit
import UIKit

/// Premium subscription through the App Store. App Store Connect needs an auto-renewable
/// subscription group with the products `monthlyProduct` and `yearlyProduct`; an introductory
/// free trial on either is picked up automatically. An extra studio voice script is the consumable
/// `extraProduct`: its transaction is finished only once the studio Worker has counted it.
final class Billing: ObservableObject {
    static let monthlyProduct = "memo_premium_monthly"
    static let yearlyProduct = "memo_premium_yearly"
    static let extraProduct = "memo_extra_script"

    enum Status {
        case connecting, ready, unavailable
    }

    /// What the store shows for one plan.
    struct Plan {
        let product: Product
        let price: String
        /// Length of the plan: the amount and D, W, M or Y.
        let period: (Int, Character)?
        /// Length of a free trial, or nil when the plan has none.
        let trial: (Int, Character)?
    }

    @Published private(set) var status: Status = .connecting
    @Published private(set) var pending = false
    @Published private(set) var plans: [String: Plan] = [:]
    /// Called on the main thread when the entitlement changes.
    var onChange: ((Bool) -> Void)?
    /// Called on the main thread with extra scripts paid for and not yet counted (transaction ids).
    var onExtraPaid: (([String]) -> Void)?
    @Published private(set) var extra: Product?
    /// The Premium subscription's transaction ids, for the studio Worker to check.
    private(set) var subscriptionTransactions: [String] = []
    private var unfinished: [String: Transaction] = [:]

    private var updates: Task<Void, Never>?

    init() {
        updates = Task.detached { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let transaction) = result {
                    if transaction.productID == Self.extraProduct {
                        await self?.paid(transaction)
                        continue
                    }
                    await transaction.finish()
                }
                await self?.refreshEntitlement()
            }
        }
    }

    deinit {
        updates?.cancel()
    }

    func plan(_ id: String) -> Plan? {
        plans[id]
    }

    /// Loads the plans and the subscription state; subscriptions can change outside the app.
    func connect() {
        Task { @MainActor in
            if plans.isEmpty {
                status = .connecting
            }
            do {
                let products = try await Product.products(for: [Self.monthlyProduct, Self.yearlyProduct,
                                                                     Self.extraProduct])
                extra = products.first { $0.id == Self.extraProduct }
                var loaded: [String: Plan] = [:]
                for product in products where product.id != Self.extraProduct {
                    loaded[product.id] = Plan(product: product, price: product.displayPrice,
                                              period: product.subscription.map { Self.duration($0.subscriptionPeriod) },
                                              trial: Self.trial(product))
                }
                plans = loaded
                status = loaded.isEmpty ? .unavailable : .ready
            } catch {
                status = .unavailable
            }
            await refreshEntitlement()
            // Extra scripts paid for earlier but not yet counted (the app closed, the network failed).
            for await result in Transaction.unfinished {
                if case .verified(let transaction) = result, transaction.productID == Self.extraProduct {
                    paid(transaction)
                }
            }
        }
    }

    @MainActor
    private func paid(_ transaction: Transaction) {
        let id = String(transaction.id)
        unfinished[id] = transaction
        onExtraPaid?([id])
    }

    /// Finishes extra scripts the Worker has counted, so another can be bought.
    @MainActor
    func finishExtras(_ ids: [String]) {
        for id in ids {
            if let transaction = unfinished.removeValue(forKey: id) {
                Task { await transaction.finish() }
            }
        }
    }

    /// Opens the App Store purchase sheet for an extra script; false when it could not be shown.
    @MainActor
    func buyExtra() async -> Bool {
        guard let extra else {
            return false
        }
        do {
            if case .success(let verification) = try await extra.purchase(),
               case .verified(let transaction) = verification {
                paid(transaction)
            }
            return true
        } catch {
            return false
        }
    }

    private static func duration(_ period: Product.SubscriptionPeriod) -> (Int, Character) {
        switch period.unit {
        case .day: return (period.value, "D")
        case .week: return (period.value, "W")
        case .month: return (period.value, "M")
        case .year: return (period.value, "Y")
        @unknown default: return (period.value, "D")
        }
    }

    private static func trial(_ product: Product) -> (Int, Character)? {
        guard let offer = product.subscription?.introductoryOffer, offer.paymentMode == .freeTrial else {
            return nil
        }
        let length = duration(offer.period)
        return (length.0 * max(1, offer.periodCount), length.1)
    }

    @MainActor
    private func refreshEntitlement() async {
        var active = false
        var transactions: [String] = []
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               [Self.monthlyProduct, Self.yearlyProduct].contains(transaction.productID),
               transaction.revocationDate == nil {
                active = true
                transactions.append(String(transaction.id))
            }
        }
        subscriptionTransactions = transactions
        // Stored from the App Store's answer so Premium also works offline.
        Premium.setPurchased(active)
        onChange?(active)
    }

    /// Opens the App Store purchase sheet; returns false when it could not be shown.
    @MainActor
    func purchase(_ plan: Plan) async -> Bool {
        do {
            let result = try await plan.product.purchase()
            switch result {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    await transaction.finish()
                }
                pending = false
                await refreshEntitlement()
            case .pending:
                pending = true
            case .userCancelled:
                break
            @unknown default:
                break
            }
            return true
        } catch {
            return false
        }
    }

    func restore() {
        Task { @MainActor in
            try? await AppStore.sync()
            await refreshEntitlement()
        }
    }

    @MainActor
    func manageSubscription() {
        if let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene {
            Task {
                try? await AppStore.showManageSubscriptions(in: scene)
            }
        } else if let url = URL(string: "https://apps.apple.com/account/subscriptions") {
            UIApplication.shared.open(url)
        }
    }
}

/// Premium entitlement: an active App Store subscription, or the test switch that only
/// development builds show.
enum Premium {
    private static let purchasedKey = "premium_purchased"
    private static let testingKey = "premium_active"

    static var isActive: Bool {
        isPurchased || isTesting
    }

    static var isPurchased: Bool {
        UserDefaults.standard.bool(forKey: purchasedKey)
    }

    static func setPurchased(_ value: Bool) {
        UserDefaults.standard.set(value, forKey: purchasedKey)
    }

    static var isTesting: Bool {
        UserDefaults.standard.bool(forKey: testingKey)
    }

    static func setTesting(_ value: Bool) {
        UserDefaults.standard.set(value, forKey: testingKey)
    }

    /// Only development builds may switch plans without a purchase.
    static var canSwitchForTesting: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
}
