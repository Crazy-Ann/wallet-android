package com.mycelium.wallet.activity.rmc;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.base.Optional;
import com.mrd.bitlib.crypto.Bip39;
import com.mrd.bitlib.crypto.HdKeyNode;
import com.mrd.bitlib.crypto.InMemoryPrivateKey;
import com.mrd.bitlib.model.Address;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.R;
import com.mycelium.wallet.Utils;
import com.mycelium.wallet.activity.rmc.json.CreateRmcOrderResponse;
import com.mycelium.wallet.colu.ColuAccount;
import com.mycelium.wallet.colu.ColuManager;
import com.mycelium.wallet.event.AccountChanged;
import com.mycelium.wallet.persistence.MetadataStorage;
import com.mycelium.wapi.api.response.Feature;
import com.mycelium.wapi.wallet.AesKeyCipher;
import com.mycelium.wapi.wallet.KeyCipher;
import com.mycelium.wapi.wallet.WalletAccount;
import com.squareup.otto.Bus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

import static android.app.Activity.RESULT_OK;

/**
 * Created by elvis on 20.06.17.
 */

public class ChooseRMCAccountFragment extends Fragment {

    private static final String TAG = "ChooseRMCAccount";
    public static final String BTC = "BTC";
    public static final String ETH = "ETH";

    BigDecimal rmcCount = BigDecimal.ZERO;
    BigDecimal ethCount = BigDecimal.ZERO;
    BigDecimal btcCount;
    String payMethod;
    String coluAddress;
    private MbwManager _mbwManager;

    @BindView(R.id.create_new_rmc)
    protected View createRmcAccount;
    @BindView(R.id.new_rmc_account)
    protected View useRmcAccount;

    @BindView(R.id.useAccountTitle)
    protected TextView useAccountTitle;

    @BindView(R.id.list_item)
    protected LinearLayout listAccounts;

    @BindView(R.id.useThisToReceive)
    protected View useThisToReceive;

    @BindView(R.id.btYes)
    protected View btYes;


    private ProgressDialog progressDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            rmcCount = (BigDecimal) getArguments().getSerializable(Keys.RMC_COUNT);
            ethCount = (BigDecimal) getArguments().getSerializable(Keys.ETH_COUNT);
            payMethod = getArguments().getString(Keys.PAY_METHOD);
            btcCount = (BigDecimal) getArguments().getSerializable(Keys.BTC_COUNT);
        }
        _mbwManager = MbwManager.getInstance(getActivity());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_rmc_choose_account, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ButterKnife.bind(this, view);

        ((TextView) view.findViewById(R.id.rmcCount)).setText(rmcCount + " RMC");

        if (!_mbwManager.getColuManager().hasAccountWithType(ColuAccount.ColuAssetType.RMC)) {
            createRmcAccount.setVisibility(View.VISIBLE);
            useRmcAccount.setVisibility(View.GONE);
        } else {
            createRmcAccount.setVisibility(View.GONE);
            useRmcAccount.setVisibility(View.VISIBLE);
            if (_mbwManager.getSelectedAccount() instanceof ColuAccount && ((ColuAccount) _mbwManager.getSelectedAccount()).getColuAsset().assetType == ColuAccount.ColuAssetType.RMC) {
                selectedWalletAccount = _mbwManager.getSelectedAccount();
            } else if (getRMCAccounts().size() == 1) {
                selectedWalletAccount = getRMCAccounts().get(0);
            }
            showAccountForAccept(false);
        }
    }

    List<ColuAccount> getRMCAccounts() {
        List<ColuAccount> result = new ArrayList<>();
        List<WalletAccount> accounts = Utils.sortAccounts(
                new ArrayList<>(_mbwManager.getColuManager().getAccounts().values())
                , _mbwManager.getMetadataStorage());
        for (WalletAccount walletAccount : accounts) {
            if (walletAccount instanceof ColuAccount && ((ColuAccount) walletAccount).getColuAsset().assetType == ColuAccount.ColuAssetType.RMC) {
                result.add((ColuAccount) walletAccount);
            }
        }
        return result;
    }

    @Override
    public void onResume() {
        super.onResume();
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        View view = getActivity().getCurrentFocus();
        if (view != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    WalletAccount selectedWalletAccount;

    private void showAccountForAccept(final boolean isNew) {
//        useRmcAccount.findViewById(R.id.tvLegacyAccountWarning).setVisibility(View.GONE);
        listAccounts.removeAllViews();
        LayoutInflater layoutInflater = LayoutInflater.from(getActivity());
        for (ColuAccount walletAccount : getRMCAccounts()) {
            View view = layoutInflater.inflate(R.layout.record_row, listAccounts, false);
            view.setTag(walletAccount);
            view.findViewById(R.id.tvBackupMissingWarning).setVisibility(View.GONE);

            ImageView icon = (ImageView) view.findViewById(R.id.ivIcon);
            Drawable drawableForAccount = Utils.getDrawableForAccount(walletAccount, true, getResources());
            if (drawableForAccount == null) {
                icon.setVisibility(View.INVISIBLE);
            } else {
                icon.setVisibility(View.VISIBLE);
                icon.setImageDrawable(drawableForAccount);
            }

            String name = _mbwManager.getMetadataStorage().getLabelByAccount(walletAccount.getId());
            String displayAddress;
            Optional<Address> receivingAddress = walletAccount.getReceivingAddress();
            if (receivingAddress.isPresent()) {
                if (name.length() == 0) {
                    // Display address in it's full glory, chopping it into three
                    displayAddress = receivingAddress.get().toMultiLineString();
                } else {
                    // Display address in short form
                    displayAddress = receivingAddress.get().getShortAddress();
                }

            } else {
                displayAddress = "";
            }
            ((TextView) view.findViewById(R.id.tvAddress)).setText(displayAddress);

            TextView labelView = (TextView) view.findViewById(R.id.tvLabel);
            if (name.length() == 0) {
                labelView.setVisibility(View.GONE);
            } else {
                labelView.setVisibility(View.VISIBLE);
                labelView.setText(name);
            }

            TextView tvBalance = ((TextView) view.findViewById(R.id.tvBalance));
            tvBalance.setVisibility(View.VISIBLE);
            String balanceString = Utils.getColuFormattedValueWithUnit(walletAccount.getCurrencyBasedBalance().confirmed);
            tvBalance.setText(balanceString);
            if (selectedWalletAccount == walletAccount) {
                view.setBackgroundColor(getResources().getColor(R.color.selectedrecord));
            }
            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectedWalletAccount = (WalletAccount) view.getTag();
                    showAccountForAccept(isNew);
                }
            });
            listAccounts.addView(view);
            layoutInflater.inflate(R.layout.divider_list, listAccounts, true);
        }
        if (listAccounts.getChildCount() > 2) {
            useAccountTitle.setText(R.string.rmc_please_select_receiving_account);
            listAccounts.removeViewAt(listAccounts.getChildCount() - 1);
            useAccountTitle.setVisibility(View.VISIBLE);
        } else if (isNew) {
            useAccountTitle.setText(R.string.rmc_your_new_account);
            useAccountTitle.setVisibility(View.VISIBLE);
        } else {
            useAccountTitle.setVisibility(View.GONE);
        }
        useThisToReceive.setVisibility(selectedWalletAccount != null ? View.VISIBLE : View.GONE);
        btYes.setEnabled(selectedWalletAccount != null);
    }

    @OnClick(R.id.btCreateNew)
    void clickCreateAcc() {
        _mbwManager.getVersionManager().showFeatureWarningIfNeeded(
                getActivity(), Feature.COLU_NEW_ACCOUNT, true, new Runnable() {
                    @Override
                    public void run() {
                        _mbwManager.runPinProtectedFunction(getActivity(), new Runnable() {
                            @Override
                            public void run() {
                                createColuAccount(ColuAccount.ColuAsset.getByType(ColuAccount.ColuAssetType.RMC), new Callback() {
                                    @Override
                                    public void created(UUID accountID) {
                                        useRmcAccount.setVisibility(View.VISIBLE);
                                        createRmcAccount.setVisibility(View.GONE);
                                        selectedWalletAccount = _mbwManager.getColuManager().getAccount(accountID);
                                        showAccountForAccept(true);
                                    }
                                });
                            }
                        });
                    }
                }
        );

    }

    private void createColuAccount(final ColuAccount.ColuAsset coluAsset, final Callback created) {

//        AlertDialog.Builder b = new AlertDialog.Builder(getActivity());
//        b.setTitle(getString(R.string.colu));
//        View diaView = LayoutInflater.from(getActivity()).inflate(R.layout.ext_colu_tos, null);
//        b.setView(diaView);
//        b.setPositiveButton(getString(R.string.agree), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialog, int which) {
        new AddColuAsyncTask(_mbwManager.getEventBus(), coluAsset, created).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
//            }
//        });
//        b.setNegativeButton(getString(R.string.dontagree), null);
//
//        AlertDialog dialog = b.create();
//
//        dialog.show();
    }


    class RmsApiTask extends AsyncTask<Void, Void, CreateRmcOrderResponse.Json> {

        private BigDecimal amountInRmc;
        private String assetAddress;
        private String paymentMethod;

        public RmsApiTask(BigDecimal amountInRmc, String assetAddress, String paymentMethod) {
            this.amountInRmc = amountInRmc;
            this.assetAddress = assetAddress;
            this.paymentMethod = paymentMethod;
        }

        private String generateCustomerId() {
            try {
                Bip39.MasterSeed seed = _mbwManager.getWalletManager(false).getMasterSeed(AesKeyCipher.defaultKeyCipher());
                HdKeyNode childNode = HdKeyNode.fromSeed(seed.getBip32Seed()).createChildNode(1234).createChildNode(7685);
                UUID uuid = ColuAccount.getGuidFromByteArray(childNode.getPublicKey().getPublicKeyBytes());
                return uuid.toString();
            } catch (Exception ex) {
                return "";
            }
        }
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected CreateRmcOrderResponse.Json doInBackground(Void... params) {
            RmcApiClient client = new RmcApiClient(_mbwManager.getNetwork());
            String customerID = generateCustomerId();
            CreateRmcOrderResponse.Json orderResponse = client.createOrder(amountInRmc.toPlainString(), assetAddress, paymentMethod, customerID);
            return orderResponse;
        }

        @Override
        protected void onPostExecute(CreateRmcOrderResponse.Json result) {
            progressDialog.dismiss();
            if (result != null) {
                if (this.paymentMethod.equals(BTC)) {
                    startActivityForResult(new Intent(Intent.ACTION_VIEW, Uri.parse(result.order.paymentDetails.uri)), Keys.PAYMENT_REQUEST_CODE);
                }

                if (this.paymentMethod.equals(ETH)) {
                    Intent intent = new Intent(getActivity(), EthPaymentRequestActivity.class);
                    intent.putExtra(Keys.ADDRESS, result.order.paymentDetails.address);
                    intent.putExtra(Keys.PAYMENT_URI, result.order.paymentDetails.uri);
                    intent.putExtra(Keys.ETH_COUNT, result.order.amount);
                    startActivity(intent);
                }
            } else {
                Toast.makeText(getActivity(), "Error getting response from RMC server", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @OnClick(R.id.btYes)
    void clickYes() {
        coluAddress = selectedWalletAccount.getReceivingAddress().get().toString();
        if (payMethod.equals(BTC)) {
            progressDialog = new ProgressDialog(getActivity());
            progressDialog.setMessage("Creating order");
            progressDialog.show();
            RmsApiTask task = new RmsApiTask(rmcCount, coluAddress, payMethod);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        } else if (payMethod.equals(ETH)) {
            progressDialog = new ProgressDialog(getActivity());
            progressDialog.setMessage("Creating order");
            progressDialog.show();
            RmsApiTask task = new RmsApiTask(rmcCount, coluAddress, payMethod);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    @OnClick(R.id.btNo)
    void clickNo() {
        getActivity().finish();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Keys.PAYMENT_REQUEST_CODE && resultCode == RESULT_OK) {
//            data.getSerializableExtra("transaction_hash");
            new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.rmc_payment_success)
                    .setPositiveButton(R.string.button_ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            getActivity().finish();
                        }
                    })
                    .create()
                    .show();
        }
    }

    interface Callback {
        void created(UUID account);
    }

    private class AddColuAsyncTask extends AsyncTask<Void, Integer, UUID> {
        private final boolean alreadyHadColuAccount;
        private Bus bus;
        private final ColuAccount.ColuAsset coluAsset;
        private ColuManager coluManager;
        private final ProgressDialog progressDialog;
        private Callback created;

        public AddColuAsyncTask(Bus bus, ColuAccount.ColuAsset coluAsset, Callback created) {
            this.bus = bus;
            this.coluAsset = coluAsset;
            this.created = created;
            this.alreadyHadColuAccount = _mbwManager.getMetadataStorage().isPairedService(MetadataStorage.PAIRED_SERVICE_COLU);
            progressDialog = ProgressDialog.show(getActivity(), getString(R.string.colu), getString(R.string.colu_creating_account, coluAsset.label));
            progressDialog.setCancelable(false);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            progressDialog.show();
        }

        @Override
        protected UUID doInBackground(Void... params) {
            _mbwManager.getMetadataStorage().setPairedService(MetadataStorage.PAIRED_SERVICE_COLU, true);
            coluManager = _mbwManager.getColuManager();
            if (coluManager == null) {
                Log.d(TAG, "Error could not obtain coluManager !");
                return null;
            } else {
                try {
                    UUID uuid = coluManager.enableAsset(coluAsset, null);
                    coluManager.startSynchronization();
                    return uuid;
                } catch (Exception e) {
                    Log.d(TAG, "Error while creating Colored Coin account for asset " + coluAsset.name + ": " + e.getMessage());
                    return null;
                }
            }
        }


        @Override
        protected void onPostExecute(UUID account) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            if (account != null) {
                _mbwManager.addExtraAccounts(coluManager);
                bus.post(new AccountChanged(account));
                created.created(account);
            } else {
                // something went wrong - clean up the half ready coluManager
                Toast.makeText(getActivity(), R.string.colu_unable_to_create_account, Toast.LENGTH_SHORT).show();
                _mbwManager.getMetadataStorage().setPairedService(MetadataStorage.PAIRED_SERVICE_COLU, alreadyHadColuAccount);
            }
        }
    }
}
