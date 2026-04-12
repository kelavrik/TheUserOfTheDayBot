package com.UserOfTheDayBot;

public class UserForBD {
    private final long ID;
    private final String USERNAME;
    private final String FIRST_NAME;
    private int userDayCounter;
    private int loserDayCounter;
    public UserForBD(long id,String username, String firstName) {
        this.ID = id;
        this.USERNAME = username;
        this.FIRST_NAME = firstName;
    }

    public String getName(){
        return getDisplayName();
    }
    public String getNotificationName(){
        return getDisplayName();
    }
    public long getID(){
        return ID;
    }
    public void setUserDayCounter(int n){
        userDayCounter = n;
    }
    public int getUserDayCounter(){
        return userDayCounter;
    }

    public int getLoserDayCounter() {
        return loserDayCounter;
    }

    public void setLoserDayCounter(int loserDayCounter) {
        this.loserDayCounter = loserDayCounter;
    }

    private String getDisplayName() {
        boolean hasUsername = USERNAME != null && !USERNAME.equals("null") && !USERNAME.isEmpty();
        boolean hasFirstName = FIRST_NAME != null && !FIRST_NAME.equals("null") && !FIRST_NAME.isEmpty();

        if (hasFirstName && hasUsername) {
            return FIRST_NAME + " (@" + USERNAME + ")";
        }
        if (hasFirstName) {
            return FIRST_NAME;
        }
        if (hasUsername) {
            return "@" + USERNAME;
        }
        return "Игрок";
    }

//    @Override
//    public String toString() {
//        return "com.UserOfTheDayBot.UserForBD{" +
//                "ID=" + ID +
//                ", USERNAME='" + USERNAME + '\'' +
//                ", FIRST_NAME='" + FIRST_NAME + '\'' +
//                ", userDayCounter=" + userDayCounter +
//                ", loserDayCounter=" + loserDayCounter +
//                '}';
//    }
}
