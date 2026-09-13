package systemrename.rulecmd;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomDialogDelegate;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.NameAssigner;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SystemRename extends BaseCommandPlugin {
    private static final String MENU = "snr_menu";
    private static final String RETURN = "snr_return";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
                           Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || params.isEmpty()) return false;

        String action = params.get(0).getString(memoryMap);
        if ("canDiscuss".equals(action)) return canDiscuss(dialog);
        if (!canDiscuss(dialog)) return false;

        if ("begin".equals(action)) {
            showMenuOrDenial(dialog);
        } else if ("system".equals(action)) {
            chooseSystemScope(dialog);
        } else if ("input".equals(action) && params.size() >= 2) {
            String target = params.get(1).getString(memoryMap);
            int starIndex = params.size() >= 3 ? params.get(2).getInt(memoryMap) : -1;
            showNameInput(dialog, target, starIndex);
        }
        return true;
    }

    private static boolean canDiscuss(InteractionDialogAPI dialog) {
        SectorEntityToken target = dialog.getInteractionTarget();
        MarketAPI market = target == null ? null : target.getMarket();
        if (market == null || !market.isPlayerOwned() || target.getActivePerson() == null) return false;
        String post = target.getActivePerson().getPostId();
        return Ranks.POST_ADMINISTRATOR.equals(post) || Ranks.POST_STATION_COMMANDER.equals(post)
                || Ranks.POST_PORTMASTER.equals(post);
    }

    private static void showMenuOrDenial(InteractionDialogAPI dialog) {
        dialog.getOptionPanel().clearOptions();
        StarSystemAPI system = dialog.getInteractionTarget().getMarket().getStarSystem();
        String official = dialog.getInteractionTarget().getActivePerson().getNameString();

        if (system == null || isStoryProtected(system)) {
            dialog.getTextPanel().addPara(official + " reviews the registry request. \"This system's designation "
                    + "is protected by standing registry and historical records, Captain. I cannot authorize that change.\"");
            addReturn(dialog);
            return;
        }
        if (!hasLargestPopulationShare(system)) {
            dialog.getTextPanel().addPara(official + " reviews the demographic ledger. \"Your colonies do not "
                    + "represent the largest share of this system's population, Captain. Without that standing, "
                    + "I cannot authorize a system-wide redesignation.\"");
            addReturn(dialog);
            return;
        }

        dialog.getTextPanel().addPara(official + " calls up the system registry. \"Your colonies represent the "
                + "largest share of the system's population, Captain. The authority to request a redesignation "
                + "is yours. Which registry entry should be amended?\"");
        dialog.getOptionPanel().addOption("Rename the system.", "snr_system");
        List<PlanetAPI> stars = getStars(system);
        for (int i = 0; i < stars.size(); i++) {
            dialog.getOptionPanel().addOption("Rename the star, \"" + stars.get(i).getName() + "\".", "snr_star_" + i);
        }
        addReturn(dialog);
        dialog.setOptionOnEscape("Return to other matters.", RETURN);
    }

    private static void chooseSystemScope(InteractionDialogAPI dialog) {
        StarSystemAPI system = dialog.getInteractionTarget().getMarket().getStarSystem();
        List<PlanetAPI> stars = getStars(system);
        boolean singleStar = stars.size() == 1;
        if (!singleStar && !hasConventionalStarSuffixes(stars)) {
            showNameInput(dialog, "system", -1);
            return;
        }

        dialog.getOptionPanel().clearOptions();
        dialog.getTextPanel().addPara(singleStar
                ? "The official indicates the linked registry entries. \"This system has a single star. Should the new designation apply to both?\""
                : "The official indicates the linked registry entries. \"This system's stars use lettered designations. Should the new name be applied to the system and each star?\"");
        dialog.getOptionPanel().addOption(singleStar ? "Rename both the system and the star."
                : "Rename the system and all of its stars.", "snr_system_both");
        dialog.getOptionPanel().addOption("Rename only the system.", "snr_system_only");
        dialog.getOptionPanel().addOption("Go back.", MENU);
        dialog.setOptionOnEscape("Go back.", MENU);
    }

    private static void showNameInput(final InteractionDialogAPI dialog, final String target, final int starIndex) {
        final StarSystemAPI system = dialog.getInteractionTarget().getMarket().getStarSystem();
        final List<PlanetAPI> stars = getStars(system);
        if ("star".equals(target) && (starIndex < 0 || starIndex >= stars.size())) {
            showMenuOrDenial(dialog);
            return;
        }
        if ("system_both".equals(target) && stars.size() != 1 && !hasConventionalStarSuffixes(stars)) {
            showMenuOrDenial(dialog);
            return;
        }

        String subject = "star".equals(target) ? "the star \"" + stars.get(starIndex).getName() + "\""
                : "system_both".equals(target) ? "the system and its star" + (stars.size() == 1 ? "" : "s") : "the system";
        dialog.getOptionPanel().clearOptions();
        dialog.getTextPanel().addPara("The official opens a secured registry form for " + subject + ".");
        dialog.getOptionPanel().addOption("Return to designation options.", MENU);
        addReturn(dialog);
        dialog.setOptionOnEscape("Return to designation options.", MENU);
        dialog.showCustomDialog(440f, 110f, new RenameDialog(dialog, system, stars, target, starIndex));
    }

    private static final class RenameDialog extends BaseCustomDialogDelegate {
        private final InteractionDialogAPI dialog;
        private final StarSystemAPI system;
        private final List<PlanetAPI> stars;
        private final String target;
        private final int starIndex;
        private TextFieldAPI field;

        private RenameDialog(InteractionDialogAPI dialog, StarSystemAPI system, List<PlanetAPI> stars,
                             String target, int starIndex) {
            this.dialog = dialog;
            this.system = system;
            this.stars = stars;
            this.target = target;
            this.starIndex = starIndex;
        }

        @Override
        public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
            TooltipMakerAPI ui = panel.createUIElement(420f, 90f, false);
            ui.addPara("Enter the new designation:", 0f);
            field = ui.addTextField(420f, 10f);
            field.setMaxChars(40);
            field.grabFocus();
            panel.addUIElement(ui).inTL(10f, 10f);
        }

        @Override
        public boolean hasCancelButton() {
            return true;
        }

        @Override
        public String getConfirmText() {
            return "Confirm";
        }

        @Override
        public String getCancelText() {
            return "Cancel";
        }

        @Override
        public void customDialogConfirm() {
            String name = field == null || field.getText() == null ? "" : field.getText().trim();
            if (name.isEmpty()) {
                dialog.getTextPanel().addPara("No designation was entered. The registry remains unchanged.");
                return;
            }

            PlanetAPI star = "star".equals(target) ? stars.get(starIndex) : stars.isEmpty() ? null : stars.get(0);
            boolean unchanged = "star".equals(target) ? name.equals(star.getName())
                    : "system_both".equals(target) ? name.equals(system.getBaseName()) && combinedStarNamesMatch(name, stars)
                    : name.equals(system.getBaseName());
            if (unchanged) {
                dialog.getTextPanel().addPara("The requested designation already matches the registry. No change is made.");
                return;
            }

            if ("star".equals(target)) {
                star.setName(name);
                refreshNavigation(system, star);
            } else {
                system.setBaseName(name);
                if ("system_both".equals(target)) {
                    for (int i = 0; i < stars.size(); i++) {
                        PlanetAPI changedStar = stars.get(i);
                        changedStar.setName(stars.size() == 1 ? name : name + suffixOf(changedStar.getName()));
                        refreshNavigation(system, changedStar);
                    }
                } else {
                    refreshNavigation(system, null);
                }
            }

            String official = dialog.getInteractionTarget().getActivePerson().getNameString();
            dialog.getTextPanel().addPara(official + " reviews the updated registry entry and nods. "
                    + "\"Confirmed. The new designation is now in effect.\"");
        }

        @Override
        public void customDialogCancel() {
            dialog.getTextPanel().addPara("The registry form is closed without changes.");
        }
    }

    private static boolean hasLargestPopulationShare(StarSystemAPI system) {
        int player = 0;
        Map<String, Integer> others = new HashMap<String, Integer>();
        for (MarketAPI market : Misc.getMarketsInLocation(system)) {
            if (market.isHidden() || market.isPlanetConditionMarketOnly()) continue;
            if (market.isPlayerOwned() || market.getFaction() != null && market.getFaction().isPlayerFaction()) {
                player += market.getSize();
            } else if (market.getFactionId() != null) {
                Integer total = others.get(market.getFactionId());
                others.put(market.getFactionId(), (total == null ? 0 : total) + market.getSize());
            }
        }
        return isStrictlyLargest(player, others.values());
    }

    static boolean isStrictlyLargest(int player, Iterable<Integer> others) {
        if (player <= 0) return false;
        for (Integer other : others) if (other != null && player <= other) return false;
        return true;
    }

    private static boolean isStoryProtected(StarSystemAPI system) {
        if (!system.isProcgen() || system.hasTag(Tags.SYSTEM_ALREADY_USED_FOR_STORY)
                || system.hasTag(Tags.STORY_CRITICAL) || hasStoryFlag(system.getMemoryWithoutUpdate())) return true;
        for (SectorEntityToken entity : system.getAllEntities()) {
            if (entity.hasTag(Tags.STORY_CRITICAL) || hasStoryFlag(entity.getMemoryWithoutUpdate())) return true;
        }
        for (MarketAPI market : Misc.getMarketsInLocation(system)) {
            if (hasStoryFlag(market.getMemoryWithoutUpdate())) return true;
        }
        return false;
    }

    private static boolean hasStoryFlag(MemoryAPI memory) {
        for (String key : memory.getKeys()) if (key.startsWith(MemFlags.STORY_CRITICAL)) return true;
        return false;
    }

    private static List<PlanetAPI> getStars(StarSystemAPI system) {
        List<PlanetAPI> stars = new ArrayList<PlanetAPI>();
        if (system.getStar() != null) stars.add(system.getStar());
        if (system.getSecondary() != null && !stars.contains(system.getSecondary())) stars.add(system.getSecondary());
        if (system.getTertiary() != null && !stars.contains(system.getTertiary())) stars.add(system.getTertiary());
        for (PlanetAPI planet : system.getPlanets()) {
            if (planet.isStar() && !stars.contains(planet)) stars.add(planet);
        }
        return stars;
    }

    private static boolean hasConventionalStarSuffixes(List<PlanetAPI> stars) {
        if (stars.size() < 2 || stars.size() > 6) return false;
        int found = 0;
        for (PlanetAPI star : stars) {
            int bit = suffixBit(star.getName(), stars.size());
            if (bit == 0 || (found & bit) != 0) return false;
            found |= bit;
        }
        return found == (1 << stars.size()) - 1;
    }

    private static boolean combinedStarNamesMatch(String name, List<PlanetAPI> stars) {
        if (stars.size() == 1) return name.equals(stars.get(0).getName());
        if (!hasConventionalStarSuffixes(stars)) return false;
        for (PlanetAPI star : stars) {
            if (!(name + suffixOf(star.getName())).equals(star.getName())) return false;
        }
        return true;
    }

    private static String suffixOf(String name) {
        if (name == null || name.length() < 2 || name.charAt(name.length() - 2) != ' ') return null;
        char letter = name.charAt(name.length() - 1);
        return letter >= 'A' && letter <= 'F' ? name.substring(name.length() - 2) : null;
    }

    private static int suffixBit(String name, int starCount) {
        String suffix = suffixOf(name);
        int index = suffix == null ? -1 : suffix.charAt(1) - 'A';
        return index >= 0 && index < starCount ? 1 << index : 0;
    }

    private static void refreshNavigation(StarSystemAPI system, PlanetAPI changedStar) {
        if (system.getAutogeneratedJumpPointsInHyper() == null) return;
        NameAssigner names = new NameAssigner(system.getConstellation());
        if (changedStar != null) names.updateJumpPointNameFor(changedStar);
        names.updateJumpPointDestinationNames(system);
    }

    private static void addReturn(InteractionDialogAPI dialog) {
        dialog.getOptionPanel().addOption("Return to other matters.", RETURN);
        dialog.setOptionOnEscape("Return to other matters.", RETURN);
    }

    public static void main(String[] args) {
        Map<String, Integer> others = new HashMap<String, Integer>();
        others.put("a", 7);
        others.put("b", 3);
        assert isStrictlyLargest(8, others.values());
        assert !isStrictlyLargest(7, others.values());
        assert !isStrictlyLargest(0, others.values());
        assert suffixBit("Example A", 2) == 1;
        assert suffixBit("Example B", 2) == 2;
        assert suffixBit("Example C", 2) == 0;
        assert suffixBit("Example F", 6) == 32;
        assert suffixBit("Example G", 6) == 0;
        assert suffixBit("Example", 2) == 0;
    }
}
