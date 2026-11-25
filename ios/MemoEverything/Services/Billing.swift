import StoreKit
import UIKit

/// Premium subscription through the App Store. App Store Connect needs an auto-renewable
/// subscription group with the products `monthlyProduct` and `yearlyProduct`; an introductory
/// free trial on either is picked up automatically.
final class Billing: ObservableObject {
    static let monthlyProduct = "memo_premium_monthly"
    static let yearlyProduct = "memo_premium_yearly"

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

    private var updates: Task<Void, Never>?

    init() {
        updates = Task.detached { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let transaction) = result {
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
                let products = try await Product.products(for: [Self.monthlyProduct, Self.yearlyProduct])
                var loaded: [String: Plan] = [:]
                for product in products {
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
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               [Self.monthlyProduct, Self.yearlyProduct].contains(transaction.productID),
               transaction.revocationDate == nil {
                active = true
            }
        }
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
