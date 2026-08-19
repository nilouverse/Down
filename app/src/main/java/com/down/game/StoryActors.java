public void spawn(String type, int q, int r) { add(type, q, r, false); }
    
    public void despawn(String name) {
        for (int i = actors.size() - 1; i >= 0; i--) {
            if (actors.get(i).name.equals(name)) { actors.remove(i); return; }
        }
    }
    
    public void hideStandins() {
        for (int i = 0; i < actors.size(); i++) {
            StoryActor a = actors.get(i);
            if (a.isEnemy()) a.hidden = true;
        }
    }
