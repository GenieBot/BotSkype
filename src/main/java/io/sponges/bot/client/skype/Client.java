package io.sponges.bot.client.skype;

import com.samczsun.skype4j.Skype;
import com.samczsun.skype4j.SkypeBuilder;
import com.samczsun.skype4j.Visibility;
import com.samczsun.skype4j.chat.Chat;
import com.samczsun.skype4j.chat.GroupChat;
import com.samczsun.skype4j.chat.IndividualChat;
import com.samczsun.skype4j.chat.messages.ReceivedMessage;
import com.samczsun.skype4j.events.EventHandler;
import com.samczsun.skype4j.events.Listener;
import com.samczsun.skype4j.events.chat.message.MessageReceivedEvent;
import com.samczsun.skype4j.events.chat.user.MultiUserAddEvent;
import com.samczsun.skype4j.events.chat.user.UserAddEvent;
import com.samczsun.skype4j.events.chat.user.UserRemoveEvent;
import com.samczsun.skype4j.events.chat.user.action.TopicUpdateEvent;
import com.samczsun.skype4j.events.contact.ContactRequestEvent;
import com.samczsun.skype4j.exceptions.ChatNotFoundException;
import com.samczsun.skype4j.exceptions.ConnectionException;
import com.samczsun.skype4j.exceptions.InvalidCredentialsException;
import com.samczsun.skype4j.exceptions.NotParticipatingException;
import com.samczsun.skype4j.formatting.Message;
import com.samczsun.skype4j.formatting.Text;
import com.samczsun.skype4j.user.User;
import io.sponges.bot.client.Bot;
import io.sponges.bot.client.event.events.*;
import io.sponges.bot.client.protocol.msg.ChannelTopicChangeMessage;
import io.sponges.bot.client.protocol.msg.ChatMessage;
import io.sponges.bot.client.protocol.msg.UserJoinMessage;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Client implements Listener {

    private static final long RELOG_DELAY = (1000 * 60) * 60; // 60 mins

    private final long start = System.currentTimeMillis();
    private final AtomicInteger messages = new AtomicInteger(0);
    private final AtomicInteger runs = new AtomicInteger(0);

    private final AtomicReference<Skype> skype = new AtomicReference<>();
    private final Bot bot;

    private String[] credentials;

    public Client() throws ConnectionException, NotParticipatingException, InvalidCredentialsException {
        this.credentials = loadCredentials(new File("credentials.json"));
        assert credentials != null;
        this.skype.set(login(credentials[0], credentials[1]));
        Timer timer = new Timer();
        this.bot = new Bot("skype", "-", "localhost", 9574);
        this.bot.getEventBus().register(CommandResponseEvent.class, event -> {
            Skype skype = this.skype.get();
            String network = event.getNetwork();
            Chat chat = skype.getChat(network);
            String response = event.getMessage();
            io.sponges.bot.client.cache.User user = event.getUser();
            String username = user.getId();
            try {
                chat.sendMessage(Message.create()
                        .with(Text.plain("(" + username + ") ").asRichText().withBold())
                        .with(Text.plain(response)));
            } catch (ConnectionException e) {
                e.printStackTrace();
            }
        });
        this.bot.getEventBus().register(StopEvent.class, event -> {
            timer.cancel();
            try {
                skype.get().logout();
            } catch (ConnectionException e) {
                e.printStackTrace();
            }
        });
        this.bot.getEventBus().register(SendRawEvent.class, event -> {
            Skype skype = this.skype.get();
            String network = event.getNetwork();
            Chat chat = skype.getChat(network);
            String response = event.getMessage();
            System.out.println("Requested to send " + response + "!");
            try {
                chat.sendMessage(response);
            } catch (ConnectionException e) {
                e.printStackTrace();
            }
        });
        this.bot.getEventBus().register(KickUserEvent.class, event -> {
            Skype skype = this.skype.get();
            String network = event.getNetwork();
            Chat chat = skype.getChat(network);
            String username = event.getUser().getId();
            try {
                ((GroupChat) chat).kick(username);
            } catch (ConnectionException e) {
                e.printStackTrace();
                try {
                    chat.sendMessage("Could not kick \"" + username + "\": " + e.getMessage());
                } catch (ConnectionException e1) {
                    e1.printStackTrace();
                }
            }
        });
        this.bot.getEventBus().register(ChangeChannelTopicEvent.class, event -> {
            Skype skype = this.skype.get();
            String network = event.getNetwork();
            Chat chat = skype.getChat(network);
            try {
                ((GroupChat) chat).setTopic(event.getTopic());
            } catch (ConnectionException e) {
                e.printStackTrace();
                try {
                    chat.sendMessage("Could not set topic to  \"" + event.getTopic() + "\": " + e.getMessage());
                } catch (ConnectionException e1) {
                    e1.printStackTrace();
                }
            }
        });

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                swap(null);
            }
        }, RELOG_DELAY, RELOG_DELAY);

        this.bot.start();
    }

    public static void main(String[] args) {
        try {
            new Client();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void swap(Chat chat) {
        new Thread(() -> {
            System.out.println(String.format("Attempting relog %s!", runs.incrementAndGet()));
            Skype newInstance;
            try {
                newInstance = login(credentials[0], credentials[1]);
            } catch (ConnectionException | NotParticipatingException | InvalidCredentialsException e) {
                e.printStackTrace();
                return;
            }
            if (newInstance == null) {
                System.out.println("New skype instance is null! Will try again next time :3");
                return;
            }
            Skype oldInstance = skype.getAndSet(newInstance);
            try {
                oldInstance.logout();
            } catch (ConnectionException e) {
                e.printStackTrace();
            }
            System.out.println("Swapped and logged out :)");
            if (chat != null) {
                try {
                    chat.sendMessage("Reloaded!");
                } catch (ConnectionException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private Skype login(String username, String password) throws ConnectionException, NotParticipatingException, InvalidCredentialsException {
        System.out.println("Logging in...");
        Skype skype = new SkypeBuilder(username, password).withExceptionHandler((errorSource, throwable, b) -> {
            if (!throwable.getMessage().contains("While submitting active (404 Not Found)")) {
                System.out.println("Error! " + errorSource + " " + throwable.getClass() + " " + throwable.getMessage() + " " + b);
                throwable.printStackTrace();
            }
        }).withAllResources().build();
        skype.login();
        skype.getEventDispatcher().registerListener(this);
        skype.subscribe();
        skype.setVisibility(Visibility.ONLINE);
        System.out.println("Logged in, subscribed & onlined!");
        return skype;
    }

    @EventHandler
    public void onMessage(MessageReceivedEvent event) {
        ReceivedMessage message = event.getMessage();
        Chat chat = message.getChat();
        String networkId = chat.getIdentity();
        boolean isPrivate = chat instanceof IndividualChat;
        User user = message.getSender();
        String userId = user.getUsername();
        String username = user.getUsername();
        boolean admin = user.getRole() == User.Role.ADMIN;
        boolean op = username.equals("joepwnsall1");
        if (username.equals("spongy.bot") || username.equals("spongybot") || username.equals("ronniepickeringscar")) return;
        String displayName = null;
        try {
            if ((displayName = user.getDisplayName()) == null) {
                displayName = username;
            }
        } catch (ConnectionException e) {
            e.printStackTrace();
        }
        long time = message.getSentTime();
        String content = message.getContent().asPlaintext();
        if (content.equalsIgnoreCase("-spongybot unfuck") && username.equals("joepwnsall1")) {
            swap(chat);
            return;
        }
        ChatMessage chatMessage = new ChatMessage(bot, networkId, "skype-dummy-channel", isPrivate, userId, username,
                displayName, admin, op, time, content);
        bot.getClient().sendMessage(chatMessage.toString());
    }

    @EventHandler
    public void onAdd(UserAddEvent event) {
        if (!(event.getChat() instanceof GroupChat)) return;
        GroupChat chat = (GroupChat) event.getChat();
        String networkId = chat.getIdentity();
        String channelId = "skype-dummy-channel";
        User added = event.getUser();
        String addedUserId = added.getUsername();
        String addedUsername = added.getUsername();
        if (addedUsername.equals("spongy.bot") || addedUsername.equals("spongybot") || addedUsername.equals("ronniepickeringscar")) return;
        String addedDisplayName = null;
        try {
            if ((addedDisplayName = added.getDisplayName()) == null) {
                addedDisplayName = addedUsername;
            }
        } catch (ConnectionException e) {
            e.printStackTrace();
        }
        boolean isAddedAdmin = added.getRole() == User.Role.ADMIN;
        boolean isAddedOp = addedUsername.equals("joepwnsall1");
        User initiator = event.getInitiator();
        String initiatorUserId = initiator.getUsername();
        String initiatorUsername = initiator.getUsername();
        String initiatorDisplayName = null;
        try {
            if ((initiatorDisplayName = initiator.getDisplayName()) == null) {
                initiatorDisplayName = initiatorUsername;
            }
        } catch (ConnectionException e) {
            e.printStackTrace();
        }
        boolean isInitiatorAdmin = initiator.getRole() == User.Role.ADMIN;
        boolean isInitiatorOp = initiatorUsername.equals("joepwnsall1");
        UserJoinMessage message = new UserJoinMessage(bot, networkId, channelId,
                addedUserId, addedUsername, addedDisplayName, isAddedAdmin, isAddedOp,
                initiatorUserId, initiatorUsername, initiatorDisplayName, isInitiatorAdmin, isInitiatorOp);
        bot.getClient().sendMessage(message.toString());
    }

    @EventHandler
    public void onMultiAdd(MultiUserAddEvent event) {
        for (int i = 1; i < event.getAllUsers().size(); i++) {
            User user = event.getAllUsers().get(i);
            UserAddEvent event1 = new UserAddEvent(user, event.getInitiator());
            onAdd(event1);
        }
    }

    @EventHandler
    public void onRemove(UserRemoveEvent event) throws ConnectionException {
        String kicked = event.getUser().getUsername();
        String kicker = event.getInitiator().getUsername();
        String chat = event.getChat().getIdentity();
        System.out.printf("%s was kicked from %s by %s!\n", kicked, chat, kicker);
    }

    @EventHandler
    public void onContact(ContactRequestEvent event) {
        try {
            System.out.println("Adding " + event.getRequest().getSender().getUsername());
            event.getRequest().accept();
            try {
                event.getRequest().getSender().getPrivateConversation().sendMessage("" +
                        "Hi, I'm SpongyBot! Sorry if it took a long time to accept your contact request, I'm a stupid robot :(" +
                        "\nTo see my commands, use -commands" +
                        "\nTo learn about me, use -about" +
                        "\nI work best in group chats, so feel free to make a new group chat or add me to one!" +
                        "\nWebsite: http://bot.sponges.io - this shows how to use the bot, some of the amazing features and if there are any issues with the bot." +
                        "\nBe sure to join the official Skype chat: https://join.skype.com/rxdbRgV0V1IV");
            } catch (ChatNotFoundException e) {
                e.printStackTrace();
            }
        } catch (ConnectionException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onTopicUpdate(TopicUpdateEvent event) {
        if (!(event.getChat() instanceof GroupChat)) return;
        GroupChat groupChat = (GroupChat) event.getChat();
        String networkId = groupChat.getIdentity();
        String channelId = "skype-dummy-channel";
        User user = event.getUser();
        String userId = user.getUsername();
        String username = user.getUsername();
        boolean admin = user.getRole() == User.Role.ADMIN;
        boolean op = username.equalsIgnoreCase("joepwnsall1");
        if (username.equals("spongy.bot") || username.equalsIgnoreCase("spongybot") || username.equalsIgnoreCase("ronniepickeringscar")) return;
        String displayName = null;
        try {
            if ((displayName = user.getDisplayName()) == null) {
                displayName = username;
            }
        } catch (ConnectionException e) {
            e.printStackTrace();
        }
        String oldTopic = event.getOldTopic() != null ? Jsoup.parse(event.getOldTopic()).text() : "null";
        String newTopic = event.getNewTopic() != null ? Jsoup.parse(event.getNewTopic()).text() : "null";
        ChannelTopicChangeMessage message = new ChannelTopicChangeMessage(bot, networkId, channelId, userId, username,
                displayName, admin, op, oldTopic, newTopic);
        bot.getClient().sendMessage(message.toString());
    }

    private String[] loadCredentials(File file) {
        if (!file.exists()) {
            BufferedWriter writer = null;
            try {
                writer = new BufferedWriter(new FileWriter(file));
                writer.write(new JSONObject().put("username", "").put("password", "").toString());
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (writer != null) {
                        writer.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }
        BufferedReader reader = null;
        StringBuilder builder = new StringBuilder();
        try {
            reader = new BufferedReader(new FileReader(file));

            String input;
            while ((input = reader.readLine()) != null) {
                builder.append("\n").append(input);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        JSONObject json = new JSONObject(builder.toString());
        String username = json.getString("username");
        String password = json.getString("password");
        return new String[] { username, password };
    }

}
