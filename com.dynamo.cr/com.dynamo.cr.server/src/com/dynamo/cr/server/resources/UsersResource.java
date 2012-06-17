package com.dynamo.cr.server.resources;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.persistence.TypedQuery;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.dynamo.cr.proto.Config.EMailTemplate;
import com.dynamo.cr.protocol.proto.Protocol.InvitationAccountInfo;
import com.dynamo.cr.protocol.proto.Protocol.RegisterUser;
import com.dynamo.cr.protocol.proto.Protocol.UserInfo;
import com.dynamo.cr.protocol.proto.Protocol.UserInfoList;
import com.dynamo.cr.protocol.proto.Protocol.UserSubscriptionInfo;
import com.dynamo.cr.protocol.proto.Protocol.UserSubscriptionState;
import com.dynamo.cr.server.ServerException;
import com.dynamo.cr.server.billing.IBillingProvider;
import com.dynamo.cr.server.mail.EMail;
import com.dynamo.cr.server.model.Invitation;
import com.dynamo.cr.server.model.InvitationAccount;
import com.dynamo.cr.server.model.ModelUtil;
import com.dynamo.cr.server.model.Product;
import com.dynamo.cr.server.model.Prospect;
import com.dynamo.cr.server.model.User;
import com.dynamo.cr.server.model.UserSubscription;
import com.dynamo.cr.server.model.UserSubscription.State;
import com.dynamo.inject.persist.Transactional;

@Path("/users")
@RolesAllowed(value = { "user" })
public class UsersResource extends BaseResource {

    static UserInfo createUserInfo(User u) {
        UserInfo.Builder b = UserInfo.newBuilder();
        b.setId(u.getId())
         .setEmail(u.getEmail())
         .setFirstName(u.getFirstName())
         .setLastName(u.getLastName());
        return b.build();
    }

    static InvitationAccountInfo createInvitationAccountInfo(InvitationAccount a) {
        InvitationAccountInfo.Builder b = InvitationAccountInfo.newBuilder();
        b.setOriginalCount(a.getOriginalCount())
.setCurrentCount(a.getCurrentCount());
        return b.build();
    }

    static UserSubscriptionInfo createUserSubscriptionInfo(UserSubscription us) {
        UserSubscriptionInfo.Builder b = UserSubscriptionInfo.newBuilder();
        UserSubscriptionState state = UserSubscriptionState.ACTIVE;
        switch (us.getState()) {
        case CANCELED:
            state = UserSubscriptionState.CANCELED;
            break;
        case PENDING:
            state = UserSubscriptionState.PENDING;
            break;
        case ACTIVE:
            state = UserSubscriptionState.ACTIVE;
            break;
        }
        b.setProduct(ResourceUtil.createProductInfo(us.getProduct())).setState(state);
        return b.build();
    }

    @GET
    @Path("/{email}")
    public UserInfo getUserInfo(@PathParam("email") String email) {
        User u = ModelUtil.findUserByEmail(em, email);
        if (u == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        return createUserInfo(u);
    }

    @PUT
    @Path("/{user}/connections/{user2}")
    @Transactional
    public void connect(@PathParam("user") String user, @PathParam("user2") String user2) {
        if (user.equals(user2)) {
            throw new ServerException("A user can not be connected to him/herself.", Response.Status.FORBIDDEN);
        }

        User u = server.getUser(em, user);
        User u2 = server.getUser(em, user2);

        u.getConnections().add(u2);
        em.persist(u);
    }

    @GET
    @Path("/{user}/connections")
    public UserInfoList getConnections(@PathParam("user") String user) {
        User u = server.getUser(em, user);

        Set<User> connections = u.getConnections();
        UserInfoList.Builder b = UserInfoList.newBuilder();
        for (User connection : connections) {
            b.addUsers(createUserInfo(connection));
        }

        return b.build();
    }

    @POST
    @Path("/")
    @RolesAllowed(value = { "admin" })
    @Transactional
    public UserInfo registerUser(RegisterUser registerUser) {
        /*
         * NOTE: Old registration method as admin role
         * OpenID registration is the only supported method. We should
         * probably remove this and related tests soon
         */

        User user = new User();
        user.setEmail(registerUser.getEmail());
        user.setFirstName(registerUser.getFirstName());
        user.setLastName(registerUser.getLastName());
        user.setPassword(registerUser.getPassword());
        em.persist(user);
        em.flush();

        UserInfo userInfo = createUserInfo(user);
        return userInfo;
    }

    @PUT
    @Path("/{user}/invite/{email}")
    @Transactional
    public Response invite(@PathParam("user") String user, @PathParam("email") String email) {
        TypedQuery<Invitation> q = em.createQuery("select i from Invitation i where i.email = :email", Invitation.class);
        List<Invitation> lst = q.setParameter("email", email).getResultList();
        if (lst.size() > 0) {
            throwWebApplicationException(Status.CONFLICT, "User already invited");
        }
        InvitationAccount a = server.getInvitationAccount(em, user);
        if (a.getCurrentCount() == 0) {
            throwWebApplicationException(Status.FORBIDDEN, "Inviter has no invitations left");
        }
        a.setCurrentCount(a.getCurrentCount() - 1);
        em.persist(a);

        // Remove prospects
        Prospect p = ModelUtil.findProspectByEmail(em, email);
        if (p != null) {
            em.remove(p);
        }

        String key = UUID.randomUUID().toString();
        User u = server.getUser(em, user);

        EMailTemplate template = server.getConfiguration().getInvitationTemplate();
        Map<String, String> params = new HashMap<String, String>();
        params.put("inviter", String.format("%s %s", u.getFirstName(), u.getLastName()));
        params.put("key", key);
        EMail emailMessage = EMail.format(template, email, params);

        Invitation invitation = new Invitation();
        invitation.setEmail(email);
        invitation.setInviterEmail(u.getEmail());
        invitation.setRegistrationKey(key);
        invitation.setInitialInvitationCount(server.getInvitationCount(a.getOriginalCount()));
        em.persist(invitation);
        server.getMailProcessor().send(em, emailMessage);
        em.flush();

        // NOTE: This is totally arbitrary. server.getMailProcessor().process()
        // should be invoked *after* the transaction is commited. Commits are
        // however container managed. Thats why we run a bit later.. budget..
        // The mail is eventually sent though as we periodically process the queue
        server.getExecutorService().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) { e.printStackTrace(); }
                server.getMailProcessor().process();
            }
        });

        return okResponse("User %s invited", email);
    }

    @GET
    @Path("/{user}/invitation_account")
    @Transactional
    public InvitationAccountInfo getInvitationAccount(@PathParam("user") String user) {
        InvitationAccount a = server.getInvitationAccount(em, user);
        return createInvitationAccountInfo(a);
    }

    @POST
    @Path("/{user}/subscription")
    @Transactional
    public void subscribe(@PathParam("user") String user,
            @QueryParam("product") String product, @QueryParam("external_id") String externalId) {
        User u = server.getUser(em, user);
        List<UserSubscription> subscriptions = em
                .createQuery("select us from UserSubscription us where us.user = :user", UserSubscription.class)
                .setParameter("user", u).getResultList();
        if (subscriptions.isEmpty()) {
            Product p = server.getProduct(em, product);
            UserSubscription subscription = new UserSubscription();
            subscription.setUser(u);
            subscription.setProduct(p);
            subscription.setExternalId(Long.parseLong(externalId));
            em.persist(subscription);
        } else {
            throwWebApplicationException(Status.CONFLICT, "User already has subscription");
        }
    }

    @GET
    @Path("/{user}/subscription")
    @Transactional
    public UserSubscriptionInfo getSubscription(@PathParam("user") String user) {
        User u = server.getUser(em, user);
        List<UserSubscription> subscriptions = em
                .createQuery("select us from UserSubscription us where us.user = :user", UserSubscription.class)
                .setParameter("user", u).getResultList();
        if (subscriptions.size() > 0) {
            return createUserSubscriptionInfo(subscriptions.get(0));
        } else {
            throwWebApplicationException(Status.NOT_FOUND, "User has no subscription");
        }
        return null;
    }

    @PUT
    @Path("/{user}/subscription")
    @Transactional
    public void setSubscription(@PathParam("user") String user, @QueryParam("product") String product,
            @QueryParam("state") String state) {
        User u = server.getUser(em, user);
        List<UserSubscription> subscriptions = em
                .createQuery("select us from UserSubscription us where us.user = :user", UserSubscription.class)
                .setParameter("user", u).getResultList();
        if (subscriptions.size() > 0) {
            UserSubscription subscription = subscriptions.get(0);
            subscription.setUser(u);
            IBillingProvider billingProvider = server.getBillingProvider();
            // Migrate subscription
            Product newProduct = server.getProduct(em, product);
            if (newProduct.getId() != subscription.getProduct().getId()) {
                if (subscription.getState() != State.ACTIVE) {
                    throwWebApplicationException(Status.CONFLICT, "Only active subscriptions can be updated");
                }
                if (billingProvider.migrateSubscription(subscription, newProduct)) {
                    subscription.setProduct(newProduct);
                } else {
                    throwWebApplicationException(Status.INTERNAL_SERVER_ERROR,
                            "Billing provider could not migrate the subscription");
                }
            }
            // Update state
            State newState = State.valueOf(state);
            State oldState = subscription.getState();
            if (oldState != newState) {
                if (newState != State.ACTIVE) {
                    throwWebApplicationException(Status.CONFLICT, "Subscriptions can only be manually activated");
                }
                if (oldState == State.CANCELED) {
                    if (!billingProvider.reactivateSubscription(subscription)) {
                        throwWebApplicationException(Status.INTERNAL_SERVER_ERROR,
                                "Billing provider could not reactivate the subscription");
                    }
                }
                subscription.setState(newState);
            }
            em.persist(subscription);
        } else {
            throwWebApplicationException(Status.NOT_FOUND, "User has no subscription");
        }
    }

    @DELETE
    @Path("/{user}/subscription")
    @Transactional
    public void deleteSubscription(@PathParam("user") String user) {
        User u = server.getUser(em, user);
        List<UserSubscription> subscriptions = em
                .createQuery("select us from UserSubscription us where us.user = :user", UserSubscription.class)
                .setParameter("user", u).getResultList();
        if (subscriptions.size() > 0) {
            UserSubscription subscription = subscriptions.get(0);
            if (server.getBillingProvider().cancelSubscription(subscription)) {
                em.remove(subscriptions.get(0));
            } else {
                throwWebApplicationException(Status.INTERNAL_SERVER_ERROR,
                        "Billing provider could not cancel the subscription");
            }
        } else {
            throwWebApplicationException(Status.NOT_FOUND, "User has no subscription");
        }
    }

}

