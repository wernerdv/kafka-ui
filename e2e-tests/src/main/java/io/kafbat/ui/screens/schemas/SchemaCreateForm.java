package io.kafbat.ui.screens.schemas;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$x;
import static com.codeborne.selenide.Selenide.$x;
import static org.openqa.selenium.By.id;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.ElementsCollection;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import io.kafbat.ui.api.model.CompatibilityLevel;
import io.kafbat.ui.api.model.SchemaType;
import io.kafbat.ui.screens.BasePage;
import io.kafbat.ui.utilities.WebUtil;
import io.qameta.allure.Step;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openqa.selenium.Keys;
import org.openqa.selenium.interactions.Actions;

public class SchemaCreateForm extends BasePage {

  protected SelenideElement schemaNameField = $x("//input[@name='subject']");
  protected SelenideElement pageTitle = $x("//h1['Edit']");
  protected SelenideElement schemaTextArea = $x("//textarea[@name='schema']");
  protected SelenideElement newSchemaInput = $("#newSchema [wrap]");
  protected SelenideElement schemaTypeDdl = $x("//ul[@name='schemaType']");
  protected SelenideElement compatibilityLevelList = $x("//ul[@name='compatibilityLevel']");
  protected SelenideElement newSchemaTextArea = $x("//div[@id='newSchema']");
  protected SelenideElement latestSchemaTextArea = $x("//div[@id='latestSchema']");
  protected SelenideElement leftVersionDdl = $(id("left-select"));
  protected SelenideElement rightVersionDdl = $(id("right-select"));
  protected ElementsCollection visibleMarkers = $$x("//div[contains(@class,'codeMarker ace_start')]");
  protected ElementsCollection elementsCompareVersionDdl = $$x("//ul[@role='listbox']/ul/li");
  protected String versionDdlElementLocator = "//ul/li[text()='Version %s']";
  protected String ddlElementLocator = "//li[@value='%s']";

  @Step
  public SchemaCreateForm waitUntilScreenReady() {
    waitUntilSpinnerDisappear();
    pageTitle.shouldBe(Condition.visible);
    return this;
  }

  @Step
  public SchemaCreateForm setSubjectName(String name) {
    schemaNameField.setValue(name);
    return this;
  }

  @Step
  public SchemaCreateForm setSchemaField(String text) {
    schemaTextArea.setValue(text);
    return this;
  }

  @Step
  public SchemaCreateForm selectSchemaTypeFromDropdown(SchemaType schemaType) {
    schemaTypeDdl.shouldBe(Condition.enabled).click();
    $x(String.format(ddlElementLocator, schemaType.getValue())).shouldBe(Condition.visible).click();
    return this;
  }

  @Step
  public SchemaCreateForm clickSubmitButton() {
    clickSubmitBtn();
    return this;
  }

  @Step
  public SchemaCreateForm selectCompatibilityLevelFromDropdown(CompatibilityLevel.CompatibilityEnum level) {
    compatibilityLevelList.shouldBe(Condition.enabled).click();
    $x(String.format(ddlElementLocator, level.getValue())).shouldBe(Condition.visible).click();
    return this;
  }

  @Step
  public SchemaCreateForm openLeftVersionDdl() {
    leftVersionDdl.shouldBe(Condition.enabled).click();
    return this;
  }

  @Step
  public SchemaCreateForm openRightVersionDdl() {
    rightVersionDdl.shouldBe(Condition.enabled).click();
    return this;
  }

  @Step
  public int getVersionsNumberFromList() {
    return elementsCompareVersionDdl.size();
  }

  @Step
  public SchemaCreateForm selectVersionFromDropDown(int versionNumberDd) {
    $x(String.format(versionDdlElementLocator, versionNumberDd)).shouldBe(Condition.visible).click();
    waitUntilSpinnerDisappear(1);
    return this;
  }

  @Step
  public int getMarkedLinesNumber() {
    return Math.toIntExact(visibleMarkers.asDynamicIterable().stream().count());
  }

  @Step
  public SchemaCreateForm setNewSchemaValue(String configJson) {
    newSchemaTextArea.shouldBe(Condition.visible).click();
    newSchemaInput.shouldBe(Condition.enabled);
    new Actions(WebDriverRunner.getWebDriver())
        .sendKeys(Keys.PAGE_UP)
        .keyDown(Keys.SHIFT)
        .sendKeys(Keys.PAGE_DOWN)
        .keyUp(Keys.SHIFT)
        .sendKeys(Keys.DELETE)
        .perform();
    setJsonInputValue(newSchemaInput, configJson);
    return this;
  }

  @Step
  public List<SelenideElement> getAllDetailsPageElements() {
    return Stream.of(compatibilityLevelList, newSchemaTextArea, latestSchemaTextArea, submitBtn, schemaTypeDdl)
        .collect(Collectors.toList());
  }

  @Step
  public boolean isSubmitBtnEnabled() {
    return WebUtil.isEnabled(submitBtn);
  }

  @Step
  public boolean isSchemaDropDownEnabled() {
    boolean enabled = true;
    try {
      String attribute = schemaTypeDdl.getAttribute("disabled");
      enabled = false;
    } catch (Throwable ignored) {
    }
    return enabled;
  }
}
