package controllers;

import controllers.actions.Ajax;
import controllers.actions.Authorization;
import forms.addressForm.SetAddress;
import forms.paymentForm.PaymentNetwork;
import forms.paymentForm.PaymentNotification;
import io.sphere.client.shop.model.Cart;
import io.sphere.client.shop.model.PaymentState;
import play.data.Form;
import play.mvc.Result;
import play.mvc.With;
import sphere.ShopController;
import utils.Payment;
import views.html.checkouts;


import java.util.List;

import static play.data.Form.form;
import static utils.ControllerHelper.displayErrors;

public class Checkouts extends ShopController {

    final static Form<SetAddress> setAddressForm = form(SetAddress.class);
    final static Form<PaymentNotification> paymentNotificationForm = form(PaymentNotification.class);


    public static Result show() {
        Cart cart = sphere().currentCart().fetch();
        Form<SetAddress> addressForm = setAddressForm.fill(new SetAddress(cart.getShippingAddress()));
        return ok(checkouts.render(cart, addressForm, 1));
    }

    public static Result showShippingAddress() {
        Cart cart = sphere().currentCart().fetch();
        Form<SetAddress> addressForm = setAddressForm.fill(new SetAddress(cart.getShippingAddress()));
        return ok(checkouts.render(cart, addressForm, 2));
    }

    @With(Ajax.class)
    public static Result setShippingAddress() {
        Cart cart;
        Form<SetAddress> form = setAddressForm.bindFromRequest();
        // Case missing or invalid form data
        if (form.hasErrors()) {
            displayErrors("set-address", form);
            cart = sphere().currentCart().fetch();
            return badRequest(checkouts.render(cart, form, 2));
        }
        // Case valid shipping address
        SetAddress setAddress = form.get();
        if (setAddress.email != null) {
            sphere().currentCart().setCustomerEmail(setAddress.email);
        }
        //sphere().currentCart().setCountry(setAddress.getCountryCode());
        cart = sphere().currentCart().setShippingAddress(setAddress.getAddress());
        setAddress.displaySuccessMessage(cart.getShippingAddress());
        return ok(checkouts.render(cart, form, 2));
    }

    public static Result getPaymentMethod() {
        Cart cart = sphere().currentCart().fetch();
        // Case no shipping address
        if (cart.getShippingAddress() == null) {
            return noContent();
        }
        // Case no payment needed
        if (cart.getTotalPrice().getAmount().doubleValue() <= 0) {
            return noContent();
        }
        // Case failed request
        String cartSnapshot = sphere().currentCart().createCartSnapshotId();
        Payment payment = new Payment(cart, cartSnapshot);
        payment.setPreselectedDeferral("ANY");
        if (!payment.doRequest(Payment.NATIVE_URL, Payment.Operation.LIST)) {
            return internalServerError();
        }
        // Case success request
        List<PaymentNetwork> paymentNetworks = payment.getApplicableNetworks();
        String referredId = payment.getReferredId();
        return ok(PaymentNetwork.getJson(paymentNetworks, referredId));
    }

    public static Result notification(String cartSnapshot) {
        // Case missing or invalid data
        Form<PaymentNotification> form = paymentNotificationForm.bindFromRequest();
        if (form.hasErrors()) {
            return badRequest();
        }
        // Case not safe order
        PaymentNotification paymentNotification = form.get();
        System.err.println("Notification " + paymentNotification.transactionId);
        System.err.println(paymentNotification.entity + " - " + paymentNotification.statusCode + " - " + paymentNotification.reasonCode);
        System.err.println(paymentNotification.resultCode + ": " + paymentNotification.resultInfo);
        //PaymentState state = paymentNotification.getPaymentState();
        Cart cart = sphere().currentCart().fetch();
        Payment payment = new Payment(cart, cartSnapshot, paymentNotification.longId);
        if (!sphere().currentCart().isSafeToCreateOrder(cartSnapshot)) {
            System.out.println("Canceling payment");
            payment.doRequest(Payment.NATIVE_URL, Payment.Operation.CANCELATION);
        } else {
            System.out.println("Closing payment");
            if (payment.doRequest(Payment.NATIVE_URL, Payment.Operation.CLOSING)) {
                System.out.println("Creating order");
                sphere().currentCart().createOrder(cartSnapshot, PaymentState.Paid);
            }
        }
        return ok();
    }

    public static Result success() {
        flash("success", "Your payment has been processed, thank you for shopping with us!");
        // TODO Redirect to somewhere
        return Categories.home(1);
    }

    public static Result failure() {
        flash("error", "Payment process aborted, please start over with another payment method.");
        // TODO Redirect to somewhere
        return badRequest("Failed...");
    }
}
